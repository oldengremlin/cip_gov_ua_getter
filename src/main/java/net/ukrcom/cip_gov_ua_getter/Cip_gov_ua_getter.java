/*
 * Copyright 2025 olden
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Консольна утиліта для збору та обробки розпоряджень про блокування доменів.
 *
 * @author olden
 */
public class Cip_gov_ua_getter {

    private static final Logger logger = LoggerFactory.getLogger(Cip_gov_ua_getter.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    /** Ліміт сторінки в urlArticles — потрібен, щоб помітити обрізання переліку. */
    private static final Pattern PAGE_SIZE = Pattern.compile("[?&]size=(\\d+)");

    /**
     * Основний процес.
     *
     * @param args аргументи командного рядка: шлях до cip.gov.ua.properties
     * (опціонально), --debug або -d для вмикання дебаг-логів
     */
    public static void main(String[] args) {
        boolean debug = false;
        String configPath = "cip.gov.ua.properties";

        for (String arg : args) {
            if (arg.equals("--debug") || arg.equals("-d")) {
                debug = true;
            } else if (!arg.isEmpty()) {
                configPath = arg;
            }
        }

        if (debug) {
            ch.qos.logback.classic.Logger rootLogger
                    = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(Level.DEBUG);
            logger.debug("Debug logging enabled");
        }
        final boolean debugMode = debug;

        try {
            Properties prop = loadConfig(configPath);
            prop.setProperty("debug", debugMode ? "true" : "false");

            String[] banKeywords = keywords(prop, "ban_keywords",
                    "блокування|обмеження доступу|реалізацію.*обмежувальних", "блокування");
            String[] unbanKeywords = keywords(prop, "unban_keywords",
                    "розблокування|припинення тимчасового", "розблокування");

            logger.debug("Loaded ban_keywords: {}", Arrays.toString(banKeywords));
            logger.debug("Loaded unban_keywords: {}", Arrays.toString(unbanKeywords));

            BlockedObjects bo = new BlockedObjects(prop).getBlockedDomainNames();

            processPrescripts(prop, bo, banKeywords, unbanKeywords);

            collectFrom("AggressorServicesParser", () -> new AggressorServicesParser(prop, debugMode), bo);
            collectFrom("PlaycityParser", () -> new PlaycityParser(prop, debugMode), bo);

            // Зберігаємо результати
            bo.storeState();
            logger.info("Successfully stored blocked domains state");

        } catch (IOException e) {
            logger.error("Failed to process articles: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process articles", e);
        } catch (JSONException e) {
            logger.error("Failed to parse JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse JSON", e);
        } catch (RuntimeException e) {
            // Не вдалося отримати перелік розпоряджень. Свідомо не викликаємо
            // storeState() — інакше перезапишемо робочий blocked.result.txt
            // неповним переліком, без доменів НЦУ.
            logger.error("Aborting without rewriting the result file: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Завантажує конфігурацію у UTF-8.
     *
     * @param configPath шлях до файлу властивостей
     * @return завантажені властивості
     */
    private static Properties loadConfig(String configPath) {
        Properties prop = new Properties();
        try (InputStreamReader input = new InputStreamReader(
                new FileInputStream(configPath), StandardCharsets.UTF_8)) {
            prop.load(input);
            logger.debug("Loaded configuration from: {}", configPath);
        } catch (IOException e) {
            logger.error("Failed to load config from {}: {}", configPath, e.getMessage(), e);
            throw new RuntimeException("Failed to load config", e);
        }
        return prop;
    }

    /**
     * Читає перелік ключових слів, розділених '|', відкидаючи порожні.
     *
     * @param prop властивості
     * @param key назва властивості
     * @param defaultValue значення за замовчуванням
     * @param fallback єдине слово, якщо після фільтрації нічого не лишилося
     * @return непорожній масив ключових слів
     */
    private static String[] keywords(Properties prop, String key, String defaultValue, String fallback) {
        String[] result = Arrays.stream(prop.getProperty(key, defaultValue).split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (result.length == 0) {
            logger.warn("No {} defined in configuration, using default: {}", key, fallback);
            return new String[]{fallback};
        }
        return result;
    }

    /**
     * Завантажує розпорядження НЦУ та витягує з них домени.
     * <p>
     * Браузер піднімається один раз на весь цей етап — раніше Chromium
     * стартував заново на кожне вкладення, що й з'їдало основну частину часу
     * «холодного» запуску. Звернення лишаються послідовними, з паузами між
     * ними: паралелити запити до cip.gov.ua не можна через анти-бот захист.
     * <p>
     * Збій <i>окремого поста</i> лише логуються — решта обробляється далі.
     * А от збій самого отримання переліку розпоряджень свідомо прокидається
     * нагору: без нього результат був би неповним, а
     * {@link BlockedObjects#storeState()} перезаписав би робочий
     * {@code blocked.result.txt}, втративши домени з розпоряджень НЦУ. Краще
     * впасти й лишити попередній результат недоторканим.
     *
     * @param prop властивості
     * @param bo накопичувач доменів
     * @param banKeywords ключові слова для постів про блокування
     * @param unbanKeywords ключові слова для постів про розблокування
     */
    private static void processPrescripts(Properties prop, BlockedObjects bo,
            String[] banKeywords, String[] unbanKeywords) {
        try (BrowserSession session = new BrowserSession(prop)) {
            CGUGetter cguGetter = new CGUGetter(prop, session);
            JSONArray posts = new ParseCGUArticlesJson(cguGetter.getJsonBody()).getPosts();

            if (posts.isEmpty()) {
                logger.warn("No posts found in JSON response");
                return;
            }
            warnIfTruncated(prop, posts.length());

            for (int i = 0; i < posts.length(); i++) {
                try {
                    processPost(posts.getJSONObject(i), prop, session, bo, banKeywords, unbanKeywords);
                } catch (Exception e) {
                    logger.error("Error processing post {}: {}", i, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Попереджає, якщо кількість отриманих постів уперлася в {@code size} з
     * {@code urlArticles}.
     * <p>
     * API cip.gov.ua не документоване й не має ознаки «є ще сторінки», тож
     * рівність кількості постів ліміту — єдиний доступний сигнал, що частина
     * розпоряджень лишилася за межами відповіді. Домени зі старіших
     * розпоряджень при цьому не зникають із результату, якщо вони є у вхідних
     * файлах {@code blocked} — але нові домени з необроблених розпоряджень
     * туди не потраплять.
     *
     * @param prop властивості
     * @param postCount кількість отриманих постів
     */
    private static void warnIfTruncated(Properties prop, int postCount) {
        String url = prop.getProperty("urlArticles", "");
        Matcher m = PAGE_SIZE.matcher(url);
        if (!m.find()) {
            return;
        }
        int size = Integer.parseInt(m.group(1));
        if (postCount >= size) {
            logger.warn("Received {} posts, which equals the 'size' limit in urlArticles ({}). "
                    + "The list is probably truncated — increase 'size' to keep processing older prescripts.",
                    postCount, size);
        }
    }

    /**
     * Обробляє один пост: фільтрує за ключовими словами і проходить вкладення.
     *
     * @param post пост із API
     * @param prop властивості
     * @param session спільна сесія браузера
     * @param bo накопичувач доменів
     * @param banKeywords ключові слова для постів про блокування
     * @param unbanKeywords ключові слова для постів про розблокування
     * @throws IOException у разі помилок читання вкладення
     */
    private static void processPost(JSONObject post, Properties prop, BrowserSession session,
            BlockedObjects bo, String[] banKeywords, String[] unbanKeywords) throws IOException {
        String title = post.getString("title");

        // Ігноруємо непубліковані пости
        if (!post.getString("status").equalsIgnoreCase("PUBLISHED")) {
            logger.warn("Skipping unpublished post: {} - {}", post.getString("date"), title);
            return;
        }

        // Перевіряємо, чи пост стосується блокування/обмеження
        if (!matchesAny(title, banKeywords, "ban_keyword")) {
            logger.warn("Skipping unrelated post: {} - {}", post.getString("date"), title);
            return;
        }

        // Визначаємо дію (блокувати чи розблокувати)
        boolean block = !matchesAny(title, unbanKeywords, "unban_keyword");

        JSONArray postAttachments = post.getJSONArray("attachments");
        for (int j = 0; j < postAttachments.length(); j++) {
            processAttachment(postAttachments.getJSONObject(j), post, prop, session, bo, block);
        }
    }

    /**
     * Перевіряє, чи заголовок відповідає хоч одному ключовому слову.
     * Некоректний регулярний вираз не зупиняє перевірку решти.
     *
     * @param title заголовок поста
     * @param patterns перелік регулярних виразів
     * @param kind назва набору для повідомлення в лозі
     * @return true, якщо є збіг
     */
    private static boolean matchesAny(String title, String[] patterns, String kind) {
        for (String keyword : patterns) {
            try {
                if (title.matches(".*" + keyword + ".*")) {
                    return true;
                }
            } catch (PatternSyntaxException e) {
                logger.warn("Invalid {} pattern '{}': {}", kind, keyword, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Обробляє одне вкладення: завантажує або читає з кешу, витягує домени.
     *
     * @param attachment вкладення з API
     * @param post батьківський пост
     * @param prop властивості
     * @param session спільна сесія браузера
     * @param bo накопичувач доменів
     * @param block true, якщо розпорядження про блокування
     * @throws IOException у разі помилок читання
     */
    private static void processAttachment(JSONObject attachment, JSONObject post, Properties prop,
            BrowserSession session, BlockedObjects bo, boolean block) throws IOException {
        String id = String.valueOf(attachment.getInt("id"));
        String mimeType = attachment.getString("mimeType");
        String fileName = attachment.getString("originalFileName");

        GetPrescript gp = new GetPrescript(prop, session, id, mimeType)
                .setOrigFileName(fileName)
                .getPrescriptFrom()
                .storePrescriptTo();

        // Оновлюємо дату файлу відповідно до post.date
        setFileDate(new File(gp.getFileName()), post.getString("date"));

        if (!mimeType.equalsIgnoreCase("text/plain")) {
            logger.info("{} {} {} {} \"{}\"",
                    LocalDateTime.now(), post.getString("date"), block ? "+" : "-", id, fileName);
            return;
        }

        for (String domain : gp.getBodyPrescript()) {
            if (domain.length() > 255) {
                logger.warn("Skipping domain due to invalid length: {}", domain);
                continue;
            }
            BlockedDomain bd = new BlockedDomain(domain, block, post.getString("date"));
            if (bo.addBlockedDomainName(bd)) {
                logger.info("{} {} [ {} \"{}\"]", LocalDateTime.now(), bd, id, fileName);
            }
        }

        if (!gp.isLocalRead()) {
            try {
                Thread.sleep(1000 + (long) (Math.random() * 1000)); // 1-2 секунди
            } catch (InterruptedException e) {
                logger.error("Interrupted during delay: {}", e.getMessage(), e);
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Забирає домени з PDF-парсера. Збій парсера не зупиняє запуск — решта
     * джерел обробляється, а результат усе одно зберігається.
     *
     * @param name назва парсера для повідомлень у лозі
     * @param factory постачальник парсера
     * @param bo накопичувач доменів
     */
    private static void collectFrom(String name, Supplier<? extends AbstractPDFParser> factory, BlockedObjects bo) {
        try {
            for (BlockedDomain bd : factory.get().parse()) {
                bo.addBlockedDomainName(bd);
            }
        } catch (Exception e) {
            logger.error("Error in {}: {}", name, e.getMessage(), e);
        }
    }

    /**
     * Встановлює дату модифікації файлу на основі дати з поста. Нічого не
     * робить, якщо файл недоступний або дата некоректна.
     *
     * @param file файл для оновлення
     * @param dateStr дата у форматі ISO 8601 (наприклад,
     * "2023-12-07T10:44:00Z")
     */
    private static void setFileDate(File file, String dateStr) {
        if (!file.exists() || !file.canWrite()) {
            logger.warn("Cannot set date for file {}: file does not exist or is not writable", file.getAbsolutePath());
            return;
        }

        try {
            Files.setLastModifiedTime(file.toPath(), FileTime.from(parseInstant(dateStr)));
            logger.debug("Set file date for {} to {}", file.getAbsolutePath(), dateStr);
        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse date '{}' for file {}: {}", dateStr, file.getAbsolutePath(), e.getMessage());
        } catch (IOException e) {
            logger.warn("Failed to set date for file {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Розбирає дату розпорядження в абсолютний момент часу.
     * <p>
     * Дати з API приходять у UTC із суфіксом "Z", тому спершу пробуємо
     * {@link OffsetDateTime} — інакше зона відкидалася б і час трактувався як
     * локальний, зсуваючи дату файлу на величину офсету. Якщо зони немає —
     * вважаємо час локальним.
     *
     * @param dateStr дата у форматі ISO 8601
     * @return момент часу
     */
    private static Instant parseInstant(String dateStr) {
        try {
            return OffsetDateTime.parse(dateStr).toInstant();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(dateStr, ISO_FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        }
    }
}

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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
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
import java.util.Set;

/**
 * Консольна утиліта для збору та обробки розпоряджень про блокування доменів.
 *
 * @author olden
 */
public class Cip_gov_ua_getter {

    private static final Logger logger = LoggerFactory.getLogger(Cip_gov_ua_getter.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    /**
     * Основний процес.
     *
     * @param args аргументи командного рядка: шлях до cip.gov.ua.properties
     * (опціонально), --debug або -d для вмикання дебаг-логів
     */
    public static void main(String[] args) {
        // Налаштування дебаг-логування
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

        try {
            // Завантаження конфігурації
            Properties prop = new Properties();
//            try (InputStream input = new FileInputStream(configPath)) {
            try (InputStreamReader input = new InputStreamReader(new FileInputStream(configPath), StandardCharsets.UTF_8)) {
                prop.load(input);
                logger.debug("Loaded configuration from: {}", configPath);
            } catch (IOException e) {
                logger.error("Failed to load config from {}: {}", configPath, e.getMessage(), e);
                throw new RuntimeException("Failed to load config", e);
            }

            prop.setProperty("debug", debug ? "true" : "false");

            // Завантаження ключових слів для фільтрації
            String banKeywordsStr = prop.getProperty("ban_keywords", "блокування|обмеження доступу|реалізацію.*обмежувальних");
            String unbanKeywordsStr = prop.getProperty("unban_keywords", "розблокування|припинення тимчасового");
            String[] banKeywords = banKeywordsStr.split("\\|");
            String[] unbanKeywords = unbanKeywordsStr.split("\\|");

            if (banKeywords.length == 0) {
                logger.warn("No ban_keywords defined in configuration, using default: блокування");
                banKeywords = new String[]{"блокування"};
            }
            if (unbanKeywords.length == 0) {
                logger.warn("No unban_keywords defined in configuration, using default: розблокування");
                unbanKeywords = new String[]{"розблокування"};
            }

            logger.debug("Loaded ban_keywords: {}", Arrays.toString(banKeywords));
            logger.debug("Loaded unban_keywords: {}", Arrays.toString(unbanKeywords));

            BlockedObjects bo = new BlockedObjects(prop).getBlockedDomainNames();

            CGUGetter cguGetter = new CGUGetter(prop);
            ParseCGUArticlesJson parseCGUArticlesJson = new ParseCGUArticlesJson(cguGetter.getJsonBody());

            JSONArray posts = parseCGUArticlesJson.getPosts();
            if (posts.isEmpty()) {
                logger.warn("No posts found in JSON response");
                bo.storeState();
                return;
            }
            for (int i = 0; i < posts.length(); i++) {
                JSONObject post = posts.getJSONObject(i);
                String title = post.getString("title");

                // Ігноруємо непубліковані пости
                if (!post.getString("status").equalsIgnoreCase("PUBLISHED")) {
                    logger.warn("Skipping unpublished post: {} - {}", post.getString("date"), title);
                    continue;
                }
                /*
                // Перевіряємо, чи пост стосується блокування/обмеження
                if (!(title.matches(".*блокування.*")
                        || title.matches(".*обмеження доступу.*")
                        || title.matches(".*реалізацію.*обмежувальних.*"))) {
                    logger.warn("Skipping unrelated post: {} - {}", post.getString("date"), title);
                    continue;
                }

                // Визначаємо дію (блокувати чи розблокувати)
                boolean block = !title.matches(".*розблокування.*") && !title.matches(".*припинення тимчасового.*");
                 */

                // Перевіряємо, чи пост стосується блокування/обмеження
                boolean isRelevant = false;
                for (String keyword : banKeywords) {
                    if (title.matches(".*" + keyword + ".*")) {
                        isRelevant = true;
                        break;
                    }
                }
                if (!isRelevant) {
                    logger.warn("Skipping unrelated post: {} - {}", post.getString("date"), title);
                    continue;
                }

                // Визначаємо дію (блокувати чи розблокувати)
                boolean block = true;
                for (String keyword : unbanKeywords) {
                    if (title.matches(".*" + keyword + ".*")) {
                        block = false;
                        break;
                    }
                }

                // Обробляємо вкладення
                JSONArray postAttachments = post.getJSONArray("attachments");
                for (int j = 0; j < postAttachments.length(); j++) {
                    JSONObject attachment = postAttachments.getJSONObject(j);
                    String id = String.valueOf(attachment.getInt("id"));
                    String mimeType = attachment.getString("mimeType");
                    String fileName = attachment.getString("originalFileName");

                    GetPrescript gp = new GetPrescript(prop, id, mimeType)
                            .setOrigFileName(fileName)
                            .getPrescriptFrom()
                            .storePrescriptTo();

                    // Оновлюємо дату файлу відповідно до post.date
                    setFileDate(new File(gp.getFileName()), post.getString("date"));

                    if (!mimeType.equalsIgnoreCase("text/plain")) {
                        logger.info("{} {} {} {} \"{}\"",
                                LocalDateTime.now(), post.getString("date"), block ? "+" : "-", id, fileName);
                        continue;
                    }

                    for (String domain : gp.getBodyPrescript()) {
                        if (domain.length() > 255) {
                            logger.warn("Skipping domain due to invalid length: {}", domain);
                            continue;
                        }
                        BlockedDomain bd = new BlockedDomain(domain, block, post.getString("date"));
                        if (bo.addBlockedDomainName(bd)) {
                            logger.info("{} {} [ {} \"{}\"]",
                                    LocalDateTime.now(), bd, id, fileName);
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
            }

            // Parse aggressor services
            AggressorServicesParser parser = new AggressorServicesParser(prop, debug);
            Set<BlockedDomain> aggressorDomains = parser.parse();
            for (BlockedDomain bd : aggressorDomains) {
                bo.addBlockedDomainName(bd);
            }

            // Parse NKEK.GOV.UA prescripts
            PlaycityParser parserNkek = new PlaycityParser(prop, debug);
            Set<BlockedDomain> nkekDomains = parserNkek.parse();
            for (BlockedDomain bd : nkekDomains) {
                bo.addBlockedDomainName(bd);
            }

            // Зберігаємо результати
            bo.storeState();
            logger.info("Successfully stored blocked domains state");

        } catch (IOException e) {
            logger.error("Failed to process articles: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process articles", e);
        } catch (JSONException e) {
            logger.error("Failed to parse JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse JSON", e);
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
            LocalDateTime dateTime = LocalDateTime.parse(dateStr, ISO_FORMATTER);
            long millis = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(millis));
            logger.debug("Set file date for {} to {}", file.getAbsolutePath(), dateStr);
        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse date '{}' for file {}: {}", dateStr, file.getAbsolutePath(), e.getMessage());
        } catch (IOException e) {
            logger.warn("Failed to set date for file {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }
}

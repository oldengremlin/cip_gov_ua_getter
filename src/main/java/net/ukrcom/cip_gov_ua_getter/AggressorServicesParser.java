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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Парсер для отримання списку доменів із сервісів держави-агресора.
 *
 * @author olden
 */
public class AggressorServicesParser extends AbstractPDFParser {

    private static final int MAX_AGE_HOURS_DEFAULT = 24;

    /** Дата в імені файлу: 25.06.2026 або 25-06-2026. */
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("(\\d{2})[.\\-](\\d{2})[.\\-](\\d{4})");

    /** Дата у шляху завантажень: /2026/06/. */
    private static final Pattern YEAR_MONTH_PATH = Pattern.compile("/(20\\d{2})/(\\d{2})/");

    private final String primaryPdfName;
    private final Duration maxAge;

    public AggressorServicesParser(Properties properties, boolean debug) {
        super(properties, debug);
        this.sourceDomain = properties.getProperty("AggressorServices_SOURCE_DOMAIN", "webportal.nrada.gov.ua");
        this.primaryPdfName = properties.getProperty("AggressorServices_PRIMARY_PDF_NAME", "Perelik.#450.2023.07.06.pdf");
        this.maxAge = Duration.ofHours(parseMaxAgeHours(properties));
    }

    /**
     * Читає максимальний вік кешу в годинах. Некоректне значення не має
     * зупиняти запуск — фолбек на типове значення з попередженням у лог.
     *
     * @param properties властивості
     * @return кількість годин, після яких кеш вважається застарілим
     */
    private static long parseMaxAgeHours(Properties properties) {
        String raw = properties.getProperty("AggressorServices_max_age_hours",
                String.valueOf(MAX_AGE_HOURS_DEFAULT)).trim();
        try {
            long hours = Long.parseLong(raw);
            if (hours <= 0) {
                logger.warn("AggressorServices_max_age_hours must be positive, using default: {}", MAX_AGE_HOURS_DEFAULT);
                return MAX_AGE_HOURS_DEFAULT;
            }
            return hours;
        } catch (NumberFormatException e) {
            logger.warn("Invalid AggressorServices_max_age_hours value '{}', using default: {}", raw, MAX_AGE_HOURS_DEFAULT);
            return MAX_AGE_HOURS_DEFAULT;
        }
    }

    @Override
    public Set<BlockedDomain> parse() {
        Set<BlockedDomain> domains = new TreeSet<>(new BlockedDomainComparator());
        String targetUrl = properties.getProperty("urlAggressorServices");

        if (targetUrl == null || targetUrl.isEmpty()) {
            logger.info("urlAggressorServices not specified in properties, skipping aggressor services parsing");
            return domains;
        }

        try {
            String pdfUrl = findPdfUrl(targetUrl);
            if (pdfUrl != null) {
                // Сайт оновлює перелік по-різному — інколи під новим іменем
                // файлу, інколи вміст під тим самим, — а локальний кеш
                // зберігається під одним фіксованим іменем незалежно від
                // цього. Тому без перевірки віку оновлення на сайті ніколи
                // не потрапляли б у результат (саме це й сталося: кеш не
                // оновлювався роками). AggressorServices_max_age_hours
                // визначає, після скількох годин кеш вважається застарілим і
                // потребує оновлення; недоступність сервера під час спроби
                // оновлення не провалює парсер — використовується стара копія.
                domains.addAll(downloadAndExtractAll(
                        Map.of(pdfUrl, manualDir.resolve(primaryPdfName)), maxAge));
            } else {
                logger.warn("Could not find PDF link on page: {}", targetUrl);
            }
        } catch (Exception e) {
            logger.error("Error parsing aggressor services: {}", e.getMessage(), e);
        }

        return domains;
    }

    /**
     * Знаходить на сторінці посилання на <b>найсвіжіший</b> PDF.
     * <p>
     * Раніше бралося просто перше посилання. Порядок елементів на сторінці —
     * не гарантія свіжості, а мовчазна помилка тут означає, що провайдер
     * блокує застарілий перелік і не проходить перевірку. Тому дату беремо з
     * самого посилання: спершу з імені файлу
     * ({@code Perelik-onovlenyj-za-25.06.2026.pdf}), потім зі шляху
     * ({@code /uploads/2026/06/}). Якщо дати не знайшлося в жодному —
     * повертаємось до першого посилання, але вже з попередженням.
     *
     * @param url сторінка з переліком
     * @return абсолютний URL найсвіжішого PDF або {@code null}
     * @throws IOException у разі помилки завантаження сторінки
     */
    private String findPdfUrl(String url) throws IOException {
        Document doc = fetchDocument(url);

        Elements links = doc.select("a[href$=.pdf]");
        if (links.isEmpty()) {
            return null;
        }

        String bestHref = null;
        LocalDate bestDate = null;
        for (Element link : links) {
            String href = link.attr("href");
            if (href.isBlank()) {
                continue;
            }
            LocalDate date = extractDate(href);
            if (date != null && (bestDate == null || date.isAfter(bestDate))) {
                bestDate = date;
                bestHref = href;
            }
        }

        if (bestHref == null) {
            bestHref = links.first().attr("href");
            logger.warn("No dated PDF link found on {}; falling back to the first of {} link(s): {}",
                    url, links.size(), bestHref);
        } else {
            logger.info("Selected most recent PDF on {} (dated {}) out of {} link(s): {}",
                    url, bestDate, links.size(), bestHref);
        }
        return toAbsoluteUrl(bestHref);
    }

    /**
     * Завантажує сторінку. Обхід перевірки сертифіката застосовується лише
     * для хостів із {@code ssl_bypass_hosts}.
     *
     * @param url адреса сторінки
     * @return розібраний документ
     * @throws IOException у разі помилки завантаження
     */
    private Document fetchDocument(String url) throws IOException {
        try {
            return Jsoup.connect(url).get();
        } catch (SSLException e) {
            if (!isSslBypassAllowed(url)) {
                throw e;
            }
            logger.warn("SSL verification failed for {}, retrying with per-connection SSL bypass: {}", url, e.getMessage());
            return Jsoup.connect(url)
                    .sslSocketFactory(createTrustAllSslSocketFactory())
                    .get();
        }
    }

    /**
     * Витягує дату з посилання: спершу {@code дд.мм.рррр} з імені файлу,
     * потім {@code /рррр/мм/} зі шляху.
     *
     * @param href посилання
     * @return дата або {@code null}, якщо не знайдено
     */
    private static LocalDate extractDate(String href) {
        Matcher m = DAY_MONTH_YEAR.matcher(href);
        if (m.find()) {
            LocalDate d = safeDate(Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            if (d != null) {
                return d;
            }
        }
        m = YEAR_MONTH_PATH.matcher(href);
        if (m.find()) {
            return safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);
        }
        return null;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * Доводить посилання до абсолютного вигляду.
     *
     * @param href посилання зі сторінки
     * @return абсолютний URL
     */
    private String toAbsoluteUrl(String href) {
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        if (!href.startsWith("http")) {
            return "https://" + sourceDomain + href;
        }
        return href;
    }

}

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
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import javax.net.ssl.SSLException;

/**
 * Парсер для отримання списку доменів із сервісів держави-агресора.
 *
 * @author olden
 */
public class AggressorServicesParser extends AbstractPDFParser {

    private static final int MAX_AGE_HOURS_DEFAULT = 24;

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
                // Сайт публікує «поточний стан» під новим іменем файлу щоразу,
                // коли оновлює перелік, а локальний кеш зберігається під одним
                // фіксованим іменем — тому без перевірки віку оновлення на
                // сайті ніколи не потрапляли б у результат (саме це й сталося:
                // кеш не оновлювався роками). AggressorServices_max_age_hours
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

    private String findPdfUrl(String url) throws IOException {
        Document doc;
        try {
            doc = Jsoup.connect(url).get();
        } catch (SSLException e) {
            logger.warn("SSL verification failed for {}, retrying with per-connection SSL bypass: {}", url, e.getMessage());
            doc = Jsoup.connect(url)
                    .sslSocketFactory(createTrustAllSslSocketFactory())
                    .get();
        }

        Element pdfLink = doc.select("a[href$=.pdf]").first();
        if (pdfLink != null) {
            String pdfUrl = pdfLink.attr("href");
            if (pdfUrl.startsWith("//")) {
                pdfUrl = "https:" + pdfUrl;
            } else if (!pdfUrl.startsWith("http")) {
                pdfUrl = "https://" + sourceDomain + pdfUrl;
            }
            return pdfUrl;
        }
        return null;
    }

}

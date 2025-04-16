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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;
import com.microsoft.playwright.*;
import java.util.Map;
import com.ibm.icu.text.SpoofChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;

/**
 * Клас зчитує перелік доменів з відповідних text/plain файлів у розпорядженнях.
 *
 * @author olden
 */
public class GetPrescript {

    private static final Logger logger = LoggerFactory.getLogger(GetPrescript.class);

    protected final String urlPrescript;
    protected String bodyPrescript;
    protected String id;
    protected String storePrescriptTo;
    protected String origFileName;
    private final String userAgent;
    private final String secChUa;
    private final Properties prop;
    private final String mimeType;
    private final boolean debug;
    private final String[] serviceSubdomains;

    // Спільний JavaScript-код для AJAX-запиту
    private static final String FETCH_SCRIPT_TEMPLATE = """
            const response = await fetch('%s', {
                method: 'GET',
                headers: {
                    'Accept': 'text/plain, */*',
                    'Sec-Ch-Ua': '%s',
                    'Sec-Fetch-Dest': 'empty',
                    'Sec-Fetch-Mode': 'cors',
                    'Sec-Fetch-Site': 'same-origin'
                }
            });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            """;

    // SpoofChecker для обробки гомогліфів
    private static final SpoofChecker SPOOF_CHECKER;
    private static final Map<String, String> SKELETON_CACHE = new ConcurrentHashMap<>();
    private boolean localRead;

    static {
        SpoofChecker.Builder builder = new SpoofChecker.Builder();
        builder.setChecks(SpoofChecker.CONFUSABLE);
        SPOOF_CHECKER = builder.build();
        logger.debug("SpoofChecker initialized for confusables");
    }

    public GetPrescript(Properties p, String i, String mt) throws IOException {
        this.localRead = true;
        this.prop = p;
        this.debug = this.prop.getProperty("debug", "false").equalsIgnoreCase("true");
        this.id = i;
        this.mimeType = mt;
        this.urlPrescript = this.prop.getProperty(
                "urlPrescript",
                "https://cip.gov.ua/services/cm/api/attachment/download?id="
        ).trim().concat(this.id);
        this.storePrescriptTo = p.getProperty(
                "store_prescript_to",
                "./Prescript"
        ).trim();
        if (!this.storePrescriptTo.endsWith("/")) {
            this.storePrescriptTo = this.storePrescriptTo.concat("/");
        }
        this.userAgent = this.prop.getProperty(
                "userAgent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        ).trim();
        this.secChUa = this.prop.getProperty(
                "secChUa",
                "\"Chromium\";v=\"129\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"129\""
        ).trim();
        String subdomains = p.getProperty("SERVICE_SUBDOMAINS",
                "www,ftp,mail,api,blog,shop,login,admin,web,secure,m,mobile,app,dev,test,m");
        this.serviceSubdomains = Arrays.stream(subdomains.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> {
                    boolean valid = s.matches("[a-zA-Z0-9-]+");
                    if (!valid && debug) {
                        logger.debug("Invalid subdomain skipped: {}", s);
                    }
                    return valid;
                })
                .toArray(String[]::new);
        if (serviceSubdomains.length == 0) {
            logger.warn("No valid service subdomains defined in SERVICE_SUBDOMAINS");
        }
    }

    public GetPrescript getPrescriptFrom() {
        try {
            if (isExists(getFileName())) {
                if (!mimeType.equalsIgnoreCase("text/plain")) {
                    logger.debug("Skipping read for non-text/plain file ID {}: {}", id, getFileName());
                    return this;
                }
                logger.info("Reading existing prescript file for ID {}: {}", id, getFileName());
                this.bodyPrescript = readLocalPrescript();
            } else if (mimeType.equalsIgnoreCase("text/plain")) {
                logger.info("Fetching prescript for ID {} from server", id);
                this.bodyPrescript = fetchPrescriptWithRetry(this.prop, 3);
                this.localRead = false;
            } else {
                logger.debug("Skipping fetch for non-text/plain file ID {}: no local file", id);
            }
        } catch (IOException ex) {
            logger.warn("Failed getPrescriptFrom: {}", id);
            this.localRead = false;
            throw new RuntimeException("Failed to get prescript for ID " + id, ex);
        }
        return this;
    }

    private String executeAjaxRequest(boolean returnAsDataUrl) {
        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setChannel("chrome")); BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(this.userAgent)
                .setLocale("uk-UA")
                .setExtraHTTPHeaders(Map.of(
                        "Accept", "text/plain, */*",
                        "Accept-Language", "uk,en-US;q=0.9,en;q=0.8,ru;q=0.7",
                        "Sec-Ch-Ua", this.secChUa,
                        "Sec-Fetch-Dest", "empty",
                        "Sec-Fetch-Mode", "cors",
                        "Sec-Fetch-Site", "same-origin"
                ))); Page page = context.newPage()) {

            // Блокуємо запити до Google Analytics і Google Tag Manager
            page.route("**/*google-analytics.com/**", route -> {
                String url = route.request().url();
                logger.debug("Blocked Google Analytics request: {}", url);
                route.abort();
            });
            page.route("**/*googletagmanager.com/**", route -> {
                String url = route.request().url();
                logger.debug("Blocked Google Tag Manager request: {}", url);
                route.abort();
            });

            // Блокуємо статичні ресурси (зображення, шрифти, стилі)
            page.route("**/*.{jpg,jpeg,png,svg,woff,woff2,ttf,css,gif,ico}", route -> {
                String url = route.request().url();
                logger.debug("Blocked static resource: {}", url);
                route.abort();
            });

            // Логування запитів і відповідей у дебаг-режимі
            if (this.debug) {
                page.onRequest(request -> logger.debug("Playwright request: {} {}", request.method(), request.url()));
                page.onResponse(response -> logger.debug("Playwright response: {} {} {}",
                        response.status(), response.request().method(), response.url()));
            }

            // Витягуємо базовий URL із urlPrescript
            String baseUrl;
            try {
                URI uri = new URI(urlPrescript);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                int port = uri.getPort();
                baseUrl = scheme + "://" + host + (port != -1 ? ":" + port : "") + "/";
            } catch (URISyntaxException e) {
                logger.warn("Failed to parse base URL from {}, falling back to default: {}", urlPrescript, e.getMessage());
                baseUrl = "https://cip.gov.ua/";
            }

            // Ініціалізація сесії
            logger.debug("Navigating to base URL: {}", baseUrl);
            page.navigate(baseUrl);
            page.waitForLoadState();

            // Формуємо JavaScript-скрипт
            String script = returnAsDataUrl
                    ? """
                    async () => {
                        %s
                        const blob = await response.blob();
                        return new Promise(resolve => {
                            const reader = new FileReader();
                            reader.onload = () => resolve(reader.result);
                            reader.readAsDataURL(blob);
                        });
                    }
                    """.formatted(FETCH_SCRIPT_TEMPLATE.formatted(this.urlPrescript, this.secChUa))
                    : """
                    async () => {
                        %s
                        return await response.text();
                    }
                    """.formatted(FETCH_SCRIPT_TEMPLATE.formatted(this.urlPrescript, this.secChUa));

            return (String) page.evaluate(script);
        }
    }

    private String readLocalPrescript() throws IOException {
        File file = new File(getFileName());
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private String fetchPrescriptWithRetry(Properties p, int maxRetries) throws IOException {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String result = executeAjaxRequest(false);
                logger.info("Successfully fetched prescript ID {} on attempt {}", this.id, attempt);
                return result;
            } catch (Exception e) {
                logger.warn("Attempt {} failed for ID {}: {}", attempt, this.id, e.getMessage());
                if (attempt == maxRetries) {
                    logger.error("Failed to fetch prescript ID {} after {} attempts: {}", this.id, maxRetries, e.getMessage());
                    try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
                        fw.write("ID: " + this.id + ", Error: " + e.getMessage() + "\n");
                    }
                    throw new IOException("Failed to fetch prescript after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(1000 + (long) (Math.random() * 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new IOException("Failed to fetch prescript: no attempts succeeded");
    }

    private String extractTld(String domain) {
        if (domain == null || domain.isEmpty()) {
            return null;
        }
        int lastDot = domain.lastIndexOf('.');
        if (lastDot == -1 || lastDot == domain.length() - 1) {
            return null;
        }
        return domain.substring(lastDot);
    }

    public String[] getBodyPrescript() {
        if (bodyPrescript.length() > 10_000_000) {
            logger.warn("Prescript ID {} is too large ({} bytes), skipping", id, bodyPrescript.length());
            return new String[0];
        }
        DomainValidator domainValidator = DomainValidator.getInstance(true);
        InetAddressValidator ipValidator = InetAddressValidator.getInstance();
        StringBuilder sb = new StringBuilder();

        for (String s : this.bodyPrescript.split("\n")) {
            String cleaned = s.trim()
                    .replaceAll("(?i)^(https?://|ftp://)", "")
                    .replaceAll("\\s+", "")
                    .toLowerCase();

            // Видаляємо субдомени
            for (String service : serviceSubdomains) {
                if (cleaned.startsWith(service + ".")) {
                    cleaned = cleaned.substring(service.length() + 1);
                    break;
                }
            }

            cleaned = cleaned
                    .replaceAll("/.*$", "");

            if (cleaned.isBlank() || cleaned.length() > 255) {
                logger.warn("Skipping domain due to invalid length: {}", cleaned);
                continue;
            }

            try {
                String idnDomain = IDN.toASCII(cleaned, IDN.ALLOW_UNASSIGNED);
                if (domainValidator.isValid(idnDomain)) {
                    String tld = extractTld(idnDomain);
                    if (tld == null || !domainValidator.isValidTld(tld)) {
                        logger.warn("Invalid TLD '{}' for domain: {}", tld, idnDomain);
                        continue;
                    }
                    sb.append(idnDomain).append("\n");
                    logger.info("Valid IDN domain: {}", idnDomain);
                } else if (ipValidator.isValid(cleaned)) {
                    logger.warn("Skipping IP address: {}", cleaned);
                    continue;
                } else {
                    logger.warn("Invalid IDN domain: {}", cleaned);
                    continue;
                }

                boolean hasNonLatin = cleaned.chars().anyMatch(c -> c > 127);
                if (hasNonLatin) {
                    String latinized = SKELETON_CACHE.computeIfAbsent(cleaned, SPOOF_CHECKER::getSkeleton);
                    String latinizedIdn = IDN.toASCII(latinized, IDN.ALLOW_UNASSIGNED);
                    if (domainValidator.isValid(latinizedIdn) && !latinizedIdn.equals(idnDomain)) {
                        String latinizedTld = extractTld(latinizedIdn);
                        if (latinizedTld == null || !domainValidator.isValidTld(latinizedTld)) {
                            logger.warn("Invalid TLD '{}' for latinized domain: {}", latinizedTld, latinizedIdn);
                            continue;
                        }
                        sb.append(latinizedIdn).append("\n");
                        logger.info("Valid latinized domain: {} (from {} -> {})", latinizedIdn, cleaned, latinized);
                    } else {
                        logger.debug("Latinized domain invalid or identical: {} (from {} -> {})", latinized, cleaned, latinized);
                    }
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Failed to process: {} ({})", cleaned, e.getMessage());
            }
        }

        return sb.length() > 0 ? sb.toString().split("\n") : new String[0];
    }

    public GetPrescript storePrescriptTo() {
        if (isExists(getFileName())) {
            logger.debug("Skipping store for ID {}: file already exists or origFileName not set", id);
            return this;
        }

        if (this.mkDir()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    String dataUrl = executeAjaxRequest(true);
                    byte[] fileContent = java.util.Base64.getDecoder().decode(dataUrl.split(",")[1]);
                    try (FileOutputStream fos = new FileOutputStream(getFileName())) {
                        fos.write(fileContent);
                    }
                    logger.info("Stored prescript {} on attempt {}", this.id, attempt);
                    return this;
                } catch (IOException e) {
                    logger.warn("Store attempt {} failed for ID {}: {}", attempt, this.id, e.getMessage());
                    if (attempt == 3) {
                        logger.error("Failed to store prescript {} after 3 attempts", this.id);
                        try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
                            fw.write("ID: " + this.id + ", Error: Failed to store after 3 attempts\n");
                        } catch (IOException ex) {
                            logger.warn("Failed to write to failed_ids.txt for ID {}: {}", this.id, ex.getMessage());
                        }
                    }
                    try {
                        Thread.sleep(1000 + (long) (Math.random() * 1000));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return this;
    }

    protected boolean mkDir() {
        File md = new File(this.storePrescriptTo);
        if (md.exists()) {
            return md.isDirectory();
        }
        return md.mkdirs();
    }

    protected boolean isExists(String fn) {
        File f = new File(fn);
        logger.debug("isExists ⮕ ({}, {})", f.exists(), f.canRead());
        return f.exists() && f.canRead();
    }

    public GetPrescript setOrigFileName(String fileName) {
        this.origFileName = fileName;
        logger.debug("Setting origFileName to {} for ID {}", this.origFileName, id);
        return this;
    }

    public String getOrigFileName() {
        return this.origFileName;
    }

    public String getFileName() {
        String fileName = this.storePrescriptTo + this.id + "~" + (origFileName != null ? origFileName : this.id + "_prescript.txt");
        logger.debug("getFileName ⮕ {} ⮕ {}", fileName, isExists(fileName));
        return fileName;
    }

    public boolean isLocalRead() {
        return this.localRead;
    }

}

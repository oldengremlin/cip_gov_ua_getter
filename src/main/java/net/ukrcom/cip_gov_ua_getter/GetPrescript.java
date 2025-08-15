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

import com.ibm.icu.text.SpoofChecker;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Клас зчитує перелік доменів з відповідних text/plain файлів у розпорядженнях.
 *
 * @author olden
 */
public class GetPrescript {
    
    private static final Logger logger = LoggerFactory.getLogger(GetPrescript.class);
    private static final long MAX_FILE_SIZE_BYTES_DEFAULT = 15_728_640; // 15 МБ

    protected final String urlPrescript;
    protected String bodyPrescript;
    protected String id;
    protected final Path storePrescriptTo;
    protected String origFileName;
    private final String userAgent;
    private final String secChUa;
    private final Properties prop;
    private final String mimeType;
    private final boolean debug;
    private final String[] serviceSubdomains;
    private final long maxFileSizeBytes;

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
        String storePrescriptToStr = p.getProperty("store_prescript_to", "./Prescript").trim();
        this.storePrescriptTo = Paths.get(storePrescriptToStr).normalize();
        try {
            Files.createDirectories(this.storePrescriptTo);
            logger.debug("Ensured directory exists: {}", this.storePrescriptTo);
        } catch (IOException e) {
            logger.error("Failed to create directory {}: {}", this.storePrescriptTo, e.getMessage(), e);
            throw new RuntimeException("Cannot create directory: " + this.storePrescriptTo, e);
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
        this.maxFileSizeBytes = Long.parseLong(
                this.prop.getProperty("max_file_size_bytes", String.valueOf(MAX_FILE_SIZE_BYTES_DEFAULT))
        );
        logger.debug("Max file size set to {} bytes", this.maxFileSizeBytes);
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
                this.bodyPrescript = fetchPrescriptWithRetry(this.prop, 5);
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
    
    private String executeAjaxRequest(boolean returnAsDataUrl) throws
            IOException {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox"))
                     .setHeadless(true)
                     .setChannel("chrome"));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
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
            //page.navigate(baseUrl);
            //page.waitForLoadState();
            page.navigate(baseUrl, new Page.NavigateOptions().setTimeout(30000));  // 30с тайм-аут
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(30000));  // Тайм-аут для wait

            /*
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
             */
            if (returnAsDataUrl) {
                logger.debug("executeAjaxRequest: {} is binary: {}", urlPrescript, returnAsDataUrl);
                // Для бінарних файлів (PDF) використовуємо прямий запит
                APIResponse response = page.request().get(urlPrescript);
                if (!response.ok()) {
                    logger.warn("HTTP  {}: {}", response.status(), response.statusText());
                    throw new IOException("HTTP " + response.status() + ": " + response.statusText());
                }
                byte[] content = response.body();
                if (content.length > maxFileSizeBytes) {
                    logger.warn("File too large: {}  bytes, max allowed: {}", content.length, maxFileSizeBytes);
                    throw new IOException("File too large: " + content.length + " bytes, max allowed: " + maxFileSizeBytes);
                } else {
                    logger.debug("File size: {}  bytes", content.length);
                }
                return "data:application/octet-stream;base64," + java.util.Base64.getEncoder().encodeToString(content);
            } else {
                logger.debug("executeAjaxRequest: {} is text: {}", urlPrescript, returnAsDataUrl);
                // Для текстових файлів використовуємо JavaScript
                String script = """
                    async () => {
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
                        return await response.text();
                    }
                    """.formatted(urlPrescript, secChUa);
                return (String) page.evaluate(script);
            }
            
        }
    }
    
    private String readLocalPrescript() throws IOException {
        File file = new File(getFileName());
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }
    
    private String fetchPrescriptWithRetry(Properties p, int maxRetries) throws
            IOException {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String result = executeAjaxRequest(false);
                logger.info("Successfully fetched prescript ID {} on attempt {}", this.id, attempt);
                return result;
            } catch (IOException e) {
                logger.warn("Attempt {} failed for ID {}: {}", attempt, this.id, e.getMessage());
                if (attempt == maxRetries) {
                    logger.error("Failed to fetch prescript ID {} after {} attempts: {}", this.id, maxRetries, e.getMessage());
                    try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
                        fw.write("ID: " + this.id + ", Error: " + e.getMessage() + "\n");
                    }
                    throw new IOException("Failed to fetch prescript after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(1000 + (long) (Math.random() * 5000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new IOException("Failed to fetch prescript: no attempts succeeded");
    }
    
    public String[] getBodyPrescript() {
        if (bodyPrescript == null || bodyPrescript.length() > 10_000_000) {
            logger.warn("Prescript ID {} is too large ({} bytes) or null, skipping", id, bodyPrescript != null ? bodyPrescript.length() : 0);
            return new String[0];
        }
        DomainValidator domainValidator = DomainValidator.getInstance(true);
        InetAddressValidator ipValidator = InetAddressValidator.getInstance();
        Set<String> validDomains = new HashSet<>();
        
        for (String s : this.bodyPrescript.split("\n")) {
            validDomains.addAll(DomainValidatorUtil.validateDomain(
                    s, serviceSubdomains, null, domainValidator, ipValidator, SPOOF_CHECKER, logger,
                    false, null, null));
        }
        
        return validDomains.toArray(String[]::new);
    }
    
    public GetPrescript storePrescriptTo() {
        if (isExists(getFileName())) {
            logger.debug("Skipping store for ID {}: file already exists or origFileName not set", id);
            return this;
        }

        // Перевірка прав доступу
        File storeDir = storePrescriptTo.toFile();
        if (!storeDir.canWrite()) {
            logger.error("Directory {} is not writable for ID {}", storePrescriptTo, id);
            try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
                fw.write("ID: " + id + ", Error: Directory not writable: " + storePrescriptTo + "\n");
            } catch (IOException ex) {
                logger.error("Can't write failed_ids.txt: {}", ex.toString());
            }
            return this;
        }

        // Перевірка вільного місця
        long freeSpace = storeDir.getFreeSpace();
        if (logger.isDebugEnabled()) {
            logger.debug("Free space in {}: {} bytes", storePrescriptTo, freeSpace);
        }
        if (freeSpace < MAX_FILE_SIZE_BYTES_DEFAULT * 2) {
            logger.error("Not enough disk space for ID {}: {} bytes available", id, freeSpace);
            try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
                fw.write("ID: " + id + ", Error: Not enough disk space (" + freeSpace + " bytes available)\n");
            } catch (IOException ex) {
                logger.error("Can't write failed_ids.txt: {}", ex.toString());
            }
            return this;
        }
        
        if (this.mkDir()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    String dataUrl = executeAjaxRequest(true);
                    logger.debug("dataUrl length: {}", dataUrl.length());
                    byte[] fileContent = java.util.Base64.getDecoder().decode(dataUrl.split(",")[1]);
                    logger.debug("fileContent length: {}", fileContent.length);
                    if (fileContent.length > maxFileSizeBytes) {
                        logger.debug("File ID {} is too large: {} bytes, max allowed: {} bytes",
                                id, fileContent.length, maxFileSizeBytes);
                        try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
                            fw.write("ID: " + id + ", Error: File too large (" + fileContent.length + " bytes, max " + maxFileSizeBytes + " bytes)\n");
                        }
                        return this;
                    }
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
        } else {
            logger.error("Failed to create directory for ID {}: {}", id, storePrescriptTo);
        }
        return this;
    }
    
    protected boolean mkDir() {
        try {
            Files.createDirectories(storePrescriptTo);
            return true;
        } catch (IOException e) {
            logger.warn("Failed to create directory {}: {}", storePrescriptTo, e.getMessage());
            return false;
        }
    }
    
    protected boolean isExists(String fn) {
        File f = new File(fn);
        logger.debug("isExists ⮕ ({}, {})", f.exists(), f.canRead());
        return f.exists() && f.canRead();
    }
    
    public GetPrescript setOrigFileName(String fileName) {
        if (fileName == null) {
            this.origFileName = null;
            return this;
        }
        String ext = "";
        String cleanedName = fileName;
        // Перевіряємо довжину в байтах UTF-8
        if (cleanedName.getBytes(StandardCharsets.UTF_8).length > 250) {
            // Витягуємо розширення
            int lastDot = fileName.lastIndexOf('.');
            String namePart = lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
            ext = lastDot > 0 ? fileName.substring(lastDot) : "";

            // Обрізаємо основну частину до 240 байт (залишаємо місце для розширення)
            if (namePart.getBytes(StandardCharsets.UTF_8).length > 240) {
                namePart = trimToUtf8Bytes(namePart, 240);
                // Обрізати до останнього пробілу
                int lastSpace = namePart.lastIndexOf(' ');
                if (lastSpace > 0) {
                    namePart = namePart.substring(0, lastSpace);
                }
            }

            // Формуємо нове ім’я
            cleanedName = namePart + (ext.isEmpty() ? "" : "...") + ext;
        }
        
        if (cleanedName.matches(".*[\\/:*?\"<>|].*")) {
            logger.warn("Invalid characters in filename for ID {}: {} : {}", id, fileName, cleanedName);
            cleanedName = id + "_prescript" + (ext.isEmpty() ? ".unknown" : ext);
        }
        
        if (cleanedName.getBytes(StandardCharsets.UTF_8).length > 255) {
            logger.warn("Cleaned filename still too long for ID {}: {}, trimming further", id, cleanedName);
            cleanedName = trimToUtf8Bytes(cleanedName, 255);
        }
        
        this.origFileName = cleanedName;
        logger.debug("Cleaned origFileName to {} for ID {}", this.origFileName, id);
        return this;
    }
    
    public static String trimToUtf8Bytes(String input, int maxBytes) {
        if (input == null) {
            return null;
        }
        
        byte[] utf8 = input.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxBytes) {
            return input;
        }
        
        int byteCount = 0;
        int endIndex = 0;
        
        for (int i = 0; i < input.length(); i++) {
            int codePoint = input.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            int chByteLen = ch.getBytes(StandardCharsets.UTF_8).length;
            
            if (byteCount + chByteLen > maxBytes) {
                break;
            }
            
            byteCount += chByteLen;
            endIndex = i + 1;
            if (Character.isHighSurrogate(input.charAt(i))) {
                i++; // Пропускаємо низьку сурогатну пару
            }
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Trimmed inString \"{}\" from {} to {} bytes ({} to {} chars)", input, utf8.length, byteCount, input.length(), endIndex);
        }
        
        return input.substring(0, endIndex);
    }
    
    public String getOrigFileName() {
        return this.origFileName;
    }
    
    public String getFileName() {
        Path filePath = storePrescriptTo.resolve(this.id + "~" + (origFileName != null ? origFileName : this.id + "_prescript.txt"));
        String fileName = filePath.toString();
        logger.debug("getFileName ⮕ {} ⮕ {}", fileName, isExists(fileName));
        return fileName;
    }
    
    public boolean isLocalRead() {
        return this.localRead;
    }
}

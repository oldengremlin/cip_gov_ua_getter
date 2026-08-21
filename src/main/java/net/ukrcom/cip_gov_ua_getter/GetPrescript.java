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
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
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
    private final String secChUa;
    private final Properties prop;
    private final BrowserSession session;
    private final String mimeType;
    private final boolean debug;
    private final String[] serviceSubdomains;
    private final long maxFileSizeBytes;

    // SpoofChecker для обробки гомогліфів
    private static final SpoofChecker SPOOF_CHECKER;
    private boolean localRead;
    private String cachedFileName;
    
    static {
        SpoofChecker.Builder builder = new SpoofChecker.Builder();
        builder.setChecks(SpoofChecker.CONFUSABLE);
        SPOOF_CHECKER = builder.build();
        logger.debug("SpoofChecker initialized for confusables");
    }
    
    public GetPrescript(Properties p, BrowserSession session, String i, String mt) throws IOException {
        this.localRead = true;
        this.prop = p;
        this.session = session;
        this.debug = this.prop.getProperty("debug", "false").equalsIgnoreCase("true");
        this.id = i;
        this.mimeType = mt;
        this.urlPrescript = this.prop.getProperty(
                "urlPrescript",
                "https://cip.gov.ua/services/cm/api/attachment/download?id="
        ).trim().concat(this.id);
        // Клас створюється на кожне вкладення — сотні разів за запуск, — тому
        // розбір конфігурації винесено в ConfigUtil із кешуванням: інакше
        // кожен екземпляр наново створював директорію, наново розбирав
        // SERVICE_SUBDOMAINS і наново парсив max_file_size_bytes.
        this.storePrescriptTo = ConfigUtil.ensureDirectory(p.getProperty("store_prescript_to", "./Prescript"));
        this.secChUa = this.prop.getProperty(
                "secChUa",
                "\"Chromium\";v=\"129\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"129\""
        ).trim();
        this.serviceSubdomains = ConfigUtil.serviceSubdomains(p);
        this.maxFileSizeBytes = ConfigUtil.positiveLong(p, "max_file_size_bytes", MAX_FILE_SIZE_BYTES_DEFAULT);
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
                this.bodyPrescript = fetchPrescriptWithRetry(5);
                this.localRead = false;
            } else {
                logger.debug("Skipping fetch for non-text/plain file ID {}: no local file", id);
            }
        } catch (IOException ex) {
            logger.warn("Failed getPrescriptFrom for ID {}: {}", id, ex.getMessage());
            this.localRead = false;
        }
        return this;
    }
    
    private String executeAjaxRequest(boolean returnAsDataUrl) throws
            IOException {
        // Сторінка береться зі спільної сесії — браузер уже піднято на весь запуск
        try (Page page = this.session.newTextPage()) {

            // Витягуємо базовий URL із urlPrescript
            String baseUrl;
            try {
                URI uri = new URI(urlPrescript);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme == null || host == null) {
                    throw new URISyntaxException(urlPrescript, "Missing scheme or host");
                }
                int port = uri.getPort();
                baseUrl = scheme + "://" + host + (port != -1 ? ":" + port : "") + "/";
            } catch (URISyntaxException e) {
                logger.warn("Failed to parse base URL from {}, falling back to default: {}", urlPrescript, e.getMessage());
                baseUrl = "https://cip.gov.ua/";
            }

            // Ініціалізація сесії
            logger.debug("Navigating to base URL: {}", baseUrl);
            page.navigate(baseUrl, new Page.NavigateOptions().setTimeout(30000));  // 30с тайм-аут
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(30000));  // Тайм-аут для wait

            if (returnAsDataUrl) {
                logger.debug("executeAjaxRequest: {} is binary: {}", urlPrescript, returnAsDataUrl);
                // Для бінарних файлів (PDF) використовуємо прямий запит
                APIResponse response = page.request().get(urlPrescript);
                if (!response.ok()) {
                    logger.warn("HTTP  {}: {}", response.status(), response.statusText());
                    throw new IOException("HTTP " + response.status() + ": " + response.statusText());
                }
                byte[] content = response.body();
                if (content == null) {
                    throw new IOException("Empty response body for URL: " + urlPrescript);
                }
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
    
    private String fetchPrescriptWithRetry(int maxRetries) throws
            IOException {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String result = executeAjaxRequest(false);
                logger.info("Successfully fetched prescript ID {} on attempt {}", this.id, attempt);
                return result;
            } catch (IOException | RuntimeException e) {
                logger.warn("Attempt {} failed for ID {}: {}", attempt, this.id, e.getMessage());
                if (attempt == maxRetries) {
                    logger.error("Failed to fetch prescript ID {} after {} attempts: {}", this.id, maxRetries, e.getMessage());
                    try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
                        fw.write("ID: " + this.id + ", Error: " + e.getMessage() + "\n");
                    }
                    throw new IOException("Failed to fetch prescript after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
                // Мережевий збій міг лишити контекст непридатним — піднімаємо браузер заново
                this.session.reset();
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
        if (bodyPrescript == null) {
            logger.warn("Prescript ID {} was not fetched or read, skipping", id);
            return new String[0];
        }
        if (bodyPrescript.length() > maxFileSizeBytes) {
            logger.warn("Prescript ID {} is too large ({} chars, max allowed: {}), skipping",
                    id, bodyPrescript.length(), maxFileSizeBytes);
            return new String[0];
        }
        DomainValidator domainValidator = DomainValidator.getInstance(true);
        InetAddressValidator ipValidator = InetAddressValidator.getInstance();
        Set<String> validDomains = new HashSet<>();
        
        for (String s : this.bodyPrescript.split("\\R")) {
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
        
        // Вміст text/plain уже отримано в getPrescriptFrom() — повторно тягнути
        // той самий файл із сервера немає сенсу. Раніше кожне нове текстове
        // вкладення завантажувалося двічі (плюс дві навігації на головну
        // cip.gov.ua), що вдвічі роздувало «холодний» запуск і стук в анти-бот.
        if (bodyPrescript != null && "text/plain".equalsIgnoreCase(mimeType)) {
            byte[] content = bodyPrescript.getBytes(StandardCharsets.UTF_8);
            if (content.length > maxFileSizeBytes) {
                logger.debug("File ID {} is too large: {} bytes, max allowed: {} bytes",
                        id, content.length, maxFileSizeBytes);
                writeFailedId("File too large (" + content.length + " bytes, max " + maxFileSizeBytes + " bytes)");
                return this;
            }
            try {
                AtomicFiles.write(Paths.get(getFileName()), content);
                logger.info("Stored prescript {} from already fetched content", this.id);
            } catch (IOException e) {
                logger.error("Failed to store prescript {}: {}", this.id, e.getMessage());
                writeFailedId("Failed to store: " + e.getMessage());
            }
            return this;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String dataUrl = executeAjaxRequest(true);
                logger.debug("dataUrl length: {}", dataUrl.length());
                String[] dataParts = dataUrl.split(",", 2);
                if (dataParts.length < 2 || dataParts[1].isEmpty()) {
                    throw new IOException("Unexpected dataUrl format for ID " + id
                            + ": missing base64 payload (dataUrl length=" + dataUrl.length() + ")");
                }
                byte[] fileContent = java.util.Base64.getDecoder().decode(dataParts[1]);
                logger.debug("fileContent length: {}", fileContent.length);
                if (fileContent.length > maxFileSizeBytes) {
                    logger.debug("File ID {} is too large: {} bytes, max allowed: {} bytes",
                            id, fileContent.length, maxFileSizeBytes);
                    writeFailedId("File too large (" + fileContent.length + " bytes, max " + maxFileSizeBytes + " bytes)");
                    return this;
                }
                AtomicFiles.write(Paths.get(getFileName()), fileContent);
                logger.info("Stored prescript {} on attempt {}", this.id, attempt);
                return this;
            } catch (IOException | IllegalArgumentException e) {
                logger.warn("Store attempt {} failed for ID {}: {}", attempt, this.id, e.getMessage());
                if (attempt == 3) {
                    logger.error("Failed to store prescript {} after 3 attempts", this.id);
                    writeFailedId("Failed to store after 3 attempts");
                    break;
                }
                // Мережевий збій міг лишити контекст непридатним — піднімаємо браузер заново
                this.session.reset();
                try {
                    Thread.sleep(1000 + (long) (Math.random() * 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return this;
    }
    
    /**
     * Дописує причину невдачі у {@code failed_ids.txt}. Сам збій запису в цей
     * файл не має нічого зупиняти — лише потрапляє в лог.
     *
     * @param reason опис помилки
     */
    private void writeFailedId(String reason) {
        try (FileWriter fw = new FileWriter("failed_ids.txt", true)) {
            fw.write("ID: " + this.id + ", Error: " + reason + "\n");
        } catch (IOException e) {
            logger.warn("Failed to write to failed_ids.txt for ID {}: {}", this.id, e.getMessage());
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
            this.cachedFileName = null;
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
        this.cachedFileName = null;
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
    
    /**
     * Шлях до локального файлу вкладення. Обчислюється один раз: раніше
     * кожен виклик (а їх 3–4 на вкладення) робив ще й {@code isExists()} —
     * тобто зайві звернення до файлової системи всередині геттера.
     *
     * @return шлях до файлу
     */
    public String getFileName() {
        if (cachedFileName == null) {
            cachedFileName = storePrescriptTo
                    .resolve(this.id + "~" + (origFileName != null ? origFileName : this.id + "_prescript.txt"))
                    .toString();
        }
        return cachedFileName;
    }
    
    public boolean isLocalRead() {
        return this.localRead;
    }
}

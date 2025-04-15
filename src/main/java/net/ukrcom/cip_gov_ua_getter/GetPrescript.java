/*
 * Click ... (ліцензія без змін)
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

    static {
        SpoofChecker.Builder builder = new SpoofChecker.Builder();
        builder.setChecks(SpoofChecker.CONFUSABLE);
        SPOOF_CHECKER = builder.build();
        logger.debug("SpoofChecker initialized for confusables");
    }

    public GetPrescript(Properties p, String i) throws IOException {
        this.id = i;
        this.urlPrescript = p.getProperty(
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
        this.userAgent = p.getProperty(
                "userAgent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        ).trim();
        this.secChUa = p.getProperty(
                "secChUa",
                "\"Chromium\";v=\"129\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"129\""
        ).trim();

        if (origFileName != null && isExists(getFileName())) {
            logger.info("Reading existing prescript file for ID {}: {}", id, getFileName());
            this.bodyPrescript = readLocalPrescript();
        } else {
            logger.info("Fetching prescript for ID {} from server", id);
            this.bodyPrescript = fetchPrescriptWithRetry(p, 3);
        }
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
                    .replaceAll("(?i)^(www\\.|ftp\\.|m\\.)", "")
                    .replaceAll("/.*$", "")
                    .replaceAll("\\s+", "")
                    .toLowerCase();

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

    public GetPrescript storePrescriptTo(String fn) {
        if (origFileName == null || isExists(origFileName)) {
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
                } catch (Exception e) {
                    logger.warn("Store attempt {} failed for ID {}: {}", attempt, this.id, e.getMessage());
                    if (attempt == 3) {
                        logger.error("Failed to store prescript {} after 3 attempts", this.id);
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
        File f = new File(getFileName());
        return f.exists() && f.canRead();
    }

    public GetPrescript setOrigFileName(String fileName) {
        System.err.println("setOrigFileName(" + fileName + ")");
        this.origFileName = fileName;
        return this;
    }

    public String getOrigFileName() {
        return this.origFileName;
    }

    public String getFileName() {
        String fileName = this.storePrescriptTo + this.id + "~" + (origFileName != null ? origFileName : "prescript.txt");
        System.err.println("getFileName ⮕ " + fileName + " " + isExists(fileName));
        return this.storePrescriptTo + this.id + "~" + (origFileName != null ? origFileName : "prescript.txt");
    }
}

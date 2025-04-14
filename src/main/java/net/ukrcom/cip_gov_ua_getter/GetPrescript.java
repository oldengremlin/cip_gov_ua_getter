/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileWriter;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import java.net.IDN;
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
    private final String userAgent;
    private final String secChUa;

    // SpoofChecker для обробки гомогліфів
    private static final SpoofChecker SPOOF_CHECKER;
    private static final Map<String, String> SKELETON_CACHE = new ConcurrentHashMap<>();

    static {
        SpoofChecker.Builder builder = new SpoofChecker.Builder();
        builder.setChecks(SpoofChecker.CONFUSABLE);
        SPOOF_CHECKER = builder.build();
        logger.debug("SpoofChecker initialized for confusables");
    }

    /**
     * Конструктор класа. Зчитує перелік доменів для блокування з прикріпленого
     * text/plain файла.
     *
     * @param p - об'єкт властивостей.
     * @param i - ідентифікатор id прикріпленого text/plain файла з доменами для
     * блокування.
     * @throws IOException
     */
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

        this.bodyPrescript = fetchPrescriptWithRetry(p, 3);
    }

    private String fetchPrescriptWithRetry(Properties p, int maxRetries) throws IOException {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
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
                        )));

                Page page = context.newPage();

                // Ініціалізація сесії
                page.navigate("https://cip.gov.ua/");
                page.waitForLoadState();

                // Виконуємо AJAX-запит
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
                        """.formatted(this.urlPrescript, this.secChUa);

                String result = (String) page.evaluate(script);
                logger.info("Successfully fetched prescript ID {} on attempt {}", this.id, attempt);
                browser.close();
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
                    Thread.sleep(1000 + (long) (Math.random() * 1000)); // 1-2 секунди
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new IOException("Failed to fetch prescript: no attempts succeeded");
    }

    /**
     * Із зчитаного переліка доменів формуємо перелік доменів для блокування.
     * Домени, що містять відмінні від латинки символи, перекодуються в idn.
     * Додаємо також латинізовані версії доменів із заміненими гомогліфами за допомогою icu4j.
     *
     * @return масив валідних доменів (IDN і латинізованих)
     */
    public String[] getBodyPrescript() {
        if (bodyPrescript.length() > 10_000_000) { // 10 МБ
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
                // Оригінальний домен у форматі IDN
                String idnDomain = IDN.toASCII(cleaned, IDN.ALLOW_UNASSIGNED);
                if (domainValidator.isValid(idnDomain)) {
                    if (!domainValidator.isValidTld(idnDomain)) {
                        logger.warn("Invalid TLD for domain: {}", idnDomain);
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

                // Генерація латинізованого домену через SpoofChecker із кешуванням
                boolean hasNonLatin = cleaned.chars().anyMatch(c -> c > 127);
                if (hasNonLatin) {
                    String latinized = SKELETON_CACHE.computeIfAbsent(cleaned, SPOOF_CHECKER::getSkeleton);
                    String latinizedIdn = IDN.toASCII(latinized, IDN.ALLOW_UNASSIGNED);
                    if (domainValidator.isValid(latinizedIdn) && !latinizedIdn.equals(idnDomain)) {
                        if (!domainValidator.isValidTld(latinizedIdn)) {
                            logger.warn("Invalid TLD for latinized domain: {}", latinizedIdn);
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

    /**
     * Зберігає копію розпорядження від НЦУ.
     *
     * @param fn - ім'я файла-розпорядження
     * @return
     */
    public GetPrescript storePrescriptTo(String fn) {
        if (this.mkDir() && !this.isExists(fn)) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try (Playwright playwright = Playwright.create()) {
                    Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
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
                            )));

                    Page page = context.newPage();

                    // Ініціалізація сесії
                    page.navigate("https://cip.gov.ua/");
                    page.waitForLoadState();

                    // Виконуємо AJAX-запит для отримання бінарного вмісту
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
                                const blob = await response.blob();
                                return new Promise(resolve => {
                                    const reader = new FileReader();
                                    reader.onload = () => resolve(reader.result);
                                    reader.readAsDataURL(blob);
                                });
                            }
                            """.formatted(this.urlPrescript, this.secChUa);

                    String dataUrl = (String) page.evaluate(script);
                    byte[] fileContent = java.util.Base64.getDecoder().decode(dataUrl.split(",")[1]);

                    try (FileOutputStream fos = new FileOutputStream(storePrescriptTo + this.id + "~" + fn)) {
                        fos.write(fileContent);
                    }
                    logger.info("Stored prescript {} on attempt {}", this.id, attempt);
                    browser.close();
                    return this;
                } catch (Exception e) {
                    logger.warn("Store attempt {} failed for ID {}: {}", attempt, this.id, e.getMessage());
                    if (attempt == 3) {
                        logger.error("Failed to store prescript {} after 3 attempts", this.id);
                    }
                    try {
                        Thread.sleep(1000 + (long) (Math.random() * 1000)); // 1-2 секунди
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
        File f = new File(this.storePrescriptTo + this.id + "~" + fn);
        return f.exists() && f.canRead() && f.canWrite();
    }
}
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.TreeSet;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Парсер для отримання списку доменів із сервісів держави-агресора.
 *
 * @author olden
 */
public abstract class AbstractPDFParser {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractPDFParser.class);
    private static final DomainValidator DOMAIN_VALIDATOR = DomainValidator.getInstance(true);
    private static final InetAddressValidator IP_VALIDATOR = InetAddressValidator.getInstance();
    private static final SpoofChecker SPOOF_CHECKER = new SpoofChecker.Builder().build();

    /**
     * Шаблон для виділення доменів із тексту PDF. Компілюється один раз —
     * раніше створювався заново на кожен документ.
     * <p>
     * Усередині символьного класу [...] зірочка є літералом, а не
     * квантифікатором, тому в класах її навмисно немає.
     */
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(?:https?://(?:www\\.)?"
            + "(?:[-a-zA-Z0-9@:%._\\+~#=]|[-\\p{L}\\p{M}]{1,256})"
            + "\\.(?:[a-zA-Z0-9()]|[\\p{L}\\p{M}]){1,6}\\b"
            + "(?:[-a-zA-Z0-9()@:%_\\+.~#?&//=]*)|"
            + "\\b(?:[a-zA-Z0-9\\p{L}\\p{M}]"
            + "(?:[a-zA-Z0-9\\p{L}\\p{M}-]*[a-zA-Z0-9\\p{L}\\p{M}])?\\.)+"
            + "(?:[a-zA-Z]{2,}|[\\p{L}\\p{M}]{2,})"
            + "(?:\\/[-a-zA-Z0-9@:%_\\+.~#?&//=]*)?\\b)");

    /**
     * Скільки PDF качаємо одночасно. Джерела — держсайти, тож тримаємо число
     * навмисно низьким: паралелізм тут потрібен лише щоб не чекати на кожен
     * файл послідовно, а не щоб навантажити сервер.
     */
    private static final int DOWNLOAD_PERMITS = 3;

    /**
     * Скільки PDF розбираємо одночасно. Робота CPU-bound, тож фактичний
     * паралелізм і так обмежений кількістю ядер; семафор стримує пам'ять —
     * PDFBox тримає документ повністю в купі.
     */
    private static final int PARSE_PERMITS = 12;

    protected final Properties properties;
    protected final Path manualDir;
    protected final boolean debug;
    protected String sourceDomain;
    protected String[] serviceSubdomains;

    public AbstractPDFParser(Properties properties, boolean debug) {
        this.properties = properties;
        this.debug = debug;
        String manualDirStr = properties.getProperty("AggressorServices_prescript_to", "./PRESCRIPT").trim();
        this.manualDir = Paths.get(manualDirStr).normalize();
        try {
            Files.createDirectories(this.manualDir);
            logger.debug("Ensured directory exists: {}", this.manualDir);
        } catch (IOException e) {
            logger.error("Failed to create directory {}: {}", this.manualDir, e.getMessage(), e);
            throw new RuntimeException("Cannot create directory: " + this.manualDir, e);
        }
        String subdomains = properties.getProperty("SERVICE_SUBDOMAINS",
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

    abstract public Set<BlockedDomain> parse();

    /**
     * Створює SSLSocketFactory, що довіряє будь-якому сертифікату.
     * Використовується лише per-connection — глобальний стан JVM не змінюється.
     *
     * @return
     */
    protected SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            TrustManager[] trustAll;
            trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] c, String a) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] c, String a) {
                    }
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            return sc.getSocketFactory();
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create trust-all SSL socket factory", e);
        }
    }

    /**
     * Завантажує PDF за URL у вказаний файл.
     * <p>
     * Якщо {@code maxAge} відсутній ({@code null}) і файл уже є на диску —
     * вважаємо його незмінним історичним документом (як рішення НКЕК за
     * унікальним URL) і завантаження пропускаємо назавжди.
     * <p>
     * Якщо {@code maxAge} задано — файл придатний, поки не застаріє. Щойно
     * його вік перевищує {@code maxAge}, наступний запуск пробує оновити
     * його з сервера. Це потрібно для джерел на кшталт переліку сервісів
     * держави-агресора: сайт оновлює «поточний стан» по-різному — інколи
     * під новим іменем файлу, інколи вміст під тим самим, — а локальний
     * кеш зберігається під одним фіксованим іменем незалежно від цього.
     * Без перевірки за віком зміни на сайті ніколи не потрапляли б у
     * результат.
     * <p>
     * Якщо спроба оновлення не вдалася (мережа, сервер недоступний), а
     * застаріла копія вже є на диску — не провалюємо джерело: лишаємо стару
     * копію і повертаємось без винятку, лише з попередженням у лог. Це
     * узгоджується з принципом «стійкість важливіша за швидке падіння» —
     * застарілий перелік кращий за відсутній.
     *
     * @param pdfUrl адреса PDF
     * @param destinationPath шлях, куди зберегти
     * @param maxAge максимальний вік кешу; {@code null} — кешувати назавжди
     * @throws IOException у разі помилки мережі чи запису, якщо відкотитися
     * на стару копію неможливо (її просто немає)
     */
    protected void downloadPdf(String pdfUrl, String destinationPath, Duration maxAge) throws IOException {
        Path destPath = Paths.get(destinationPath);
        boolean cacheExists = Files.exists(destPath);

        if (cacheExists) {
            if (maxAge == null) {
                logger.debug("PDF cached indefinitely, skipping download: {}", destPath);
                return;
            }
            Duration age = Duration.between(Files.getLastModifiedTime(destPath).toInstant(), Instant.now())
                    .truncatedTo(ChronoUnit.SECONDS);
            if (age.compareTo(maxAge) < 0) {
                logger.info("Cached PDF is still fresh (age {}, max {}), skipping download: {}", age, maxAge, destPath);
                return;
            }
            logger.debug("Cached PDF is stale (age {}, max {}), attempting refresh: {}", age, maxAge, destPath);
        }

        Files.createDirectories(destPath.getParent());
        Path tempPath = destPath.resolveSibling(destPath.getFileName() + ".tmp");
        try {
            try {
                downloadViaConnection(pdfUrl, tempPath, null);
            } catch (SSLException e) {
                logger.warn("SSL verification failed for {}, retrying with per-connection SSL bypass: {}", pdfUrl, e.getMessage());
                Files.deleteIfExists(tempPath);
                downloadViaConnection(pdfUrl, tempPath, createTrustAllSslSocketFactory());
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempPath);
            if (cacheExists) {
                logger.warn("Failed to refresh stale cached PDF, falling back to on-disk copy: {} ({})",
                        destPath, e.getMessage());
                return;
            }
            throw e;
        }
        try {
            Files.move(tempPath, destPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, destPath, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("Downloaded fresh PDF: {}", destPath);
    }

    private void downloadViaConnection(String pdfUrl, Path destPath, SSLSocketFactory sslSocketFactory) throws IOException {
        URLConnection connection;
        try {
            connection = new URI(pdfUrl).toURL().openConnection();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid PDF URL: " + pdfUrl, e);
        }
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        if (sslSocketFactory != null && connection instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) connection;
            httpsConn.setSSLSocketFactory(sslSocketFactory);
            httpsConn.setHostnameVerifier((hostname, session) -> true);
        }

        try (InputStream in = connection.getInputStream(); ReadableByteChannel rbc = Channels.newChannel(in); FileOutputStream fos = new FileOutputStream(destPath.toFile())) {
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        }
    }

    public String prepareDocument(String text) {
        return text.replace("\n", "");
    }

    /**
     * Завантажує й розбирає набір PDF паралельно на віртуальних потоках JDK 21.
     * <p>
     * Кожен файл проходить власний конвеєр «завантажити → розібрати», без
     * бар'єра між фазами: доки одні файли ще качаються, інші вже
     * розбираються. Дві окремі квоти обмежують навантаження — на мережу
     * ({@link #DOWNLOAD_PERMITS}) і на пам'ять ({@link #PARSE_PERMITS}).
     * <p>
     * Збій окремого файлу не зупиняє решту: помилка потрапляє в лог, а решта
     * конвеєрів працює далі.
     *
     * @param targets мапа «URL → шлях, куди зберегти»
     * @param maxAge максимальний вік кешу для кожного PDF; {@code null} —
     * кешувати назавжди (див. {@link #downloadPdf})
     * @return об'єднаний перелік доменів з усіх PDF
     */
    protected Set<BlockedDomain> downloadAndExtractAll(Map<String, Path> targets, Duration maxAge) {
        Set<BlockedDomain> domains = new TreeSet<>(new BlockedDomainComparator());
        if (targets.isEmpty()) {
            return domains;
        }

        Semaphore downloadLimit = new Semaphore(DOWNLOAD_PERMITS);
        Semaphore parseLimit = new Semaphore(PARSE_PERMITS);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Set<BlockedDomain>>> futures = new ArrayList<>(targets.size());
            for (Map.Entry<String, Path> target : targets.entrySet()) {
                String url = target.getKey();
                Path path = target.getValue();
                futures.add(executor.submit(() -> {
                    downloadLimit.acquire();
                    try {
                        downloadPdf(url, path.toString(), maxAge);
                    } finally {
                        downloadLimit.release();
                    }
                    parseLimit.acquire();
                    try {
                        return extractDomainsFromPDF(path.toString());
                    } finally {
                        parseLimit.release();
                    }
                }));
            }

            for (Future<Set<BlockedDomain>> future : futures) {
                try {
                    domains.addAll(future.get());
                } catch (ExecutionException e) {
                    logger.error("Error processing PDF: {}", e.getCause().getMessage(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while collecting PDF results");
                    break;
                }
            }
        }

        if (debug) {
            logger.debug("Extracted {} domains from {} PDF(s)", domains.size(), targets.size());
        }
        return domains;
    }

    protected Set<BlockedDomain> extractDomainsFromPDF(String filePath) {
        Set<BlockedDomain> domains = new TreeSet<>(new BlockedDomainComparator());

        try {
            File file = new File(filePath);
            try (PDDocument document = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                String cleanedText = prepareDocument(text);

                logger.debug("Document: {}", cleanedText);

                Matcher domainMatcher = DOMAIN_PATTERN.matcher(cleanedText);

                while (domainMatcher.find()) {
                    String match = domainMatcher.group();
                    DomainValidatorUtil.validateDomain(
                            match, serviceSubdomains, sourceDomain, DOMAIN_VALIDATOR, IP_VALIDATOR, SPOOF_CHECKER, logger,
                            true, LocalDateTime.now(), domains);

                }
            }
        } catch (IOException e) {
            logger.error("Error processing PDF file {}: {}", filePath, e.getMessage(), e);
        }

        return domains;
    }

}

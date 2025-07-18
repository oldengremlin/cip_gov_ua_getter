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
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер для отримання списку доменів із сервісів держави-агресора.
 *
 * @author olden
 */
public abstract class PDFParser {

    protected static final Logger logger = LoggerFactory.getLogger(PDFParser.class);
    private static final DomainValidator DOMAIN_VALIDATOR = DomainValidator.getInstance(true);
    private static final InetAddressValidator IP_VALIDATOR = InetAddressValidator.getInstance();
    private static final SpoofChecker SPOOF_CHECKER = new SpoofChecker.Builder().build();

    protected final Properties properties;
    protected final Path manualDir;
    protected final boolean debug;
    protected String sourceDomain;
    protected String[] serviceSubdomains;

    public PDFParser(Properties properties, boolean debug) {
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

    protected void disableSSLCertificateVerification() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }
        };

        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

        HostnameVerifier allHostsValid = (hostname, session) -> true;
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
    }

    protected void downloadPdf(String pdfUrl, String destinationPath) throws
            IOException {
        Path destPath = Paths.get(destinationPath);
        if (Files.exists(destPath)) {
            logger.debug("PDF already exists: {}", destPath);
            return;
        }
        Files.createDirectories(destPath.getParent());
        URL url = new URL(pdfUrl);
        try (InputStream in = url.openStream();
             ReadableByteChannel rbc = Channels.newChannel(in);
             FileOutputStream fos = new FileOutputStream(destPath.toFile())) {
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        }
        logger.debug("Downloaded PDF to: {}", destPath);
    }

    public String prepareDocument(String text) {
        return text.replaceAll("\n", "");
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

                String domainPattern = "(?:https?://(?:www\\.)?"
                        + "(?:[-a-zA-Z0-9@:%._\\+~#=]|[-\\p{L}\\p{M}*]{1,256})"
                        + "\\.(?:[a-zA-Z0-9()]|[\\p{L}\\p{M}*]){1,6}\\b"
                        + "(?:[-a-zA-Z0-9()@:%_\\+.~#?&//=]*)|"
                        + "\\b(?:[a-zA-Z0-9\\p{L}\\p{M}*]"
                        + "(?:[a-zA-Z0-9\\p{L}\\p{M}*-]*[a-zA-Z0-9\\p{L}\\p{M}*])?\\.)+"
                        + "(?:[a-zA-Z]{2,}|[\\p{L}\\p{M}*]{2,})"
                        + "(?:\\/[-a-zA-Z0-9@:%_\\+.~#?&//=]*)?\\b)";
                Pattern domainRegex = Pattern.compile(domainPattern);
                Matcher domainMatcher = domainRegex.matcher(cleanedText);

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

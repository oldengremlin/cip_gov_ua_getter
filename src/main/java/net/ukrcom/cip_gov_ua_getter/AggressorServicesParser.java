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
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AggressorServicesParser {

    private static final Logger logger = LoggerFactory.getLogger(AggressorServicesParser.class);
    private static final DomainValidator DOMAIN_VALIDATOR = DomainValidator.getInstance(true);
    private static final InetAddressValidator IP_VALIDATOR = InetAddressValidator.getInstance();
    private static final SpoofChecker SPOOF_CHECKER = new SpoofChecker.Builder().build();
    private static final ConcurrentHashMap<String, String> SKELETON_CACHE = new ConcurrentHashMap<>();

    private final Properties properties;
    private final String manualDir;
    private final boolean debug;
    private final String sourceDomain;
    private final String primaryPdfName;
    private final String[] serviceSubdomains;

    public AggressorServicesParser(Properties properties, boolean debug) {
        this.properties = properties;
        this.debug = debug;
        this.manualDir = properties.getProperty("AggressorServices_prescript_to", "./PRESCRIPT");
        this.sourceDomain = properties.getProperty("AggressorServices_SOURCE_DOMAIN", "webportal.nrada.gov.ua");
        this.primaryPdfName = properties.getProperty("AggressorServices_PRIMARY_PDF_NAME", "Perelik.#450.2023.07.06.pdf");
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
        new File(manualDir).mkdirs();
    }

    public Set<BlockedDomain> parse() {
        Set<BlockedDomain> domains = new TreeSet<>(new BlockedDomainComparator());
        String targetUrl = properties.getProperty("urlAggressorServices");

        if (targetUrl == null || targetUrl.isEmpty()) {
            logger.info("urlAggressorServices not specified in properties, skipping aggressor services parsing");
            return domains;
        }

        try {
            disableSSLCertificateVerification();
            String pdfUrl = findPdfUrl(targetUrl);
            if (pdfUrl != null) {
                String primaryPdfPath = manualDir + primaryPdfName;
                downloadPdf(pdfUrl, primaryPdfPath);
                logger.info("Successfully downloaded PDF to: {}", primaryPdfPath);

                domains.addAll(extractDomainsFromPDF(primaryPdfPath));
                if (debug) {
                    logger.debug("Extracted {} domains from aggressor services PDF", domains.size());
                }
            } else {
                logger.warn("Could not find PDF link on page: {}", targetUrl);
            }
        } catch (Exception e) {
            logger.error("Error parsing aggressor services: {}", e.getMessage(), e);
        }

        return domains;
    }

    private void disableSSLCertificateVerification() throws Exception {
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

    private String findPdfUrl(String url) throws IOException {
        Document doc = Jsoup.connect(url).get();
        Element pdfLink = doc.select("a[href$=.pdf]").first();
        if (pdfLink != null) {
            String pdfUrl = pdfLink.attr("href");
            if (!pdfUrl.startsWith("http")) {
                pdfUrl = "https://" + sourceDomain + pdfUrl;
            }
            return pdfUrl;
        }
        return null;
    }

    private void downloadPdf(String pdfUrl, String destinationPath) throws IOException {
        File file = new File(destinationPath);
        if (file.exists()) {
            logger.debug("PDF already exists: {}", destinationPath);
            return;
        }
        URL url = new URL(pdfUrl);
        try (InputStream in = url.openStream(); ReadableByteChannel rbc = Channels.newChannel(in); FileOutputStream fos = new FileOutputStream(destinationPath)) {
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        }
        logger.debug("Downloaded PDF to: {}", destinationPath);
    }

    private Set<BlockedDomain> extractDomainsFromPDF(String filePath) {
        Set<BlockedDomain> domains = new TreeSet<>(new BlockedDomainComparator());

        try {
            File file = new File(filePath);
            try (PDDocument document = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                String cleanedText = text.replaceAll("\n", "");

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
                    extractAndAddDomains(match, domains);
                }
            }
        } catch (IOException e) {
            logger.error("Error processing PDF file {}: {}", filePath, e.getMessage(), e);
        }

        return domains;
    }

    private void extractAndAddDomains(String urlOrDomain, Set<BlockedDomain> domains) {
        try {
            String domain = urlOrDomain.replaceFirst("https?://", "");

            for (String service : serviceSubdomains) {
                if (domain.startsWith(service + ".")) {
                    domain = domain.substring(service.length() + 1);
                    break;
                }
            }

            int endIndex = domain.indexOf("/");
            if (endIndex != -1) {
                domain = domain.substring(0, endIndex);
            }
            endIndex = domain.indexOf(":");
            if (endIndex != -1) {
                domain = domain.substring(0, endIndex);
            }
            endIndex = domain.indexOf("?");
            if (endIndex != -1) {
                domain = domain.substring(0, endIndex);
            }

            logger.info("urlOrDomain {} ⮕ {}", urlOrDomain, domain);

            if (IP_VALIDATOR.isValid(domain)) {
                if (debug) {
                    logger.debug("Skipping IP address: {}", domain);
                }
                return;
            }

            String asciiDomain = domain.toLowerCase();
            try {
                asciiDomain = IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED);
            } catch (Exception e) {
                if (debug) {
                    logger.debug("Invalid domain for IDN conversion: {}", domain);
                }
            }

            if (DOMAIN_VALIDATOR.isValid(asciiDomain) && asciiDomain.length() <= 253
                    && !asciiDomain.equals(sourceDomain)) {
                BlockedDomain bd = new BlockedDomain(asciiDomain, true, "");
                domains.add(bd);
                if (debug && !domain.equals(asciiDomain)) {
                    logger.debug("Added ASCII domain: {} (original: {})", asciiDomain, domain);
                }
            }

            String latinDomain = checkHomographs(domain);
            if (!latinDomain.equals(domain) && DOMAIN_VALIDATOR.isValid(latinDomain)
                    && latinDomain.length() <= 253 && !latinDomain.equals(sourceDomain)) {
                BlockedDomain bd = new BlockedDomain(latinDomain.toLowerCase(), true, "");
                domains.add(bd);
                if (debug) {
                    logger.debug("Added homograph domain: {} (original: {})", latinDomain, domain);
                }
            }
        } catch (Exception e) {
            if (debug) {
                logger.debug("Error processing domain {}: {}", urlOrDomain, e.getMessage());
            }
        }
    }

    private String checkHomographs(String domain) {
        String normalized = domain.toLowerCase();
        try {
            boolean hasNonLatin = normalized.chars().anyMatch(c -> c > 127);
            if (!hasNonLatin) {
                return normalized;
            }

            String idnDomain = IDN.toASCII(normalized, IDN.ALLOW_UNASSIGNED);

            String latinized = SKELETON_CACHE.computeIfAbsent(normalized, key -> {
                String nfkc = Normalizer.normalize(key, Normalizer.Form.NFKC);
                return nfkc.toLowerCase();
            });

            String latinizedIdn = IDN.toASCII(latinized, IDN.ALLOW_UNASSIGNED);

            if (!latinizedIdn.equals(idnDomain)) {
                if (debug) {
                    logger.debug("Detected homograph: {} -> {}", normalized, latinizedIdn);
                }
                return latinizedIdn;
            }
        } catch (Exception e) {
            if (debug) {
                logger.debug("Homograph check failed for {}: {}", normalized, e.getMessage());
            }
        }
        return normalized;
    }
}

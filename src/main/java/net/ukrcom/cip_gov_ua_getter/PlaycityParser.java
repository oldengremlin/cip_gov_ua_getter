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
public class PlaycityParser {

    private static final Logger logger = LoggerFactory.getLogger(PlaycityParser.class);
    private static final DomainValidator DOMAIN_VALIDATOR = DomainValidator.getInstance(true);
    private static final InetAddressValidator IP_VALIDATOR = InetAddressValidator.getInstance();
    private static final SpoofChecker SPOOF_CHECKER = new SpoofChecker.Builder().build();

    private final Properties properties;
    private final Path manualDir;
    private final boolean debug;
    private final String sourceDomain;
    private final String[] serviceSubdomains;
    private final String[] urlPdfs;

    public PlaycityParser(Properties properties, boolean debug) {
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
        this.sourceDomain = "nkek.gov.ua";
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
        this.urlPdfs = Arrays.stream(properties.getProperty("urlPdfs", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    public Set<BlockedDomain> parse() {
        Set<BlockedDomain> domains = new TreeSet<>(new BlockedDomainComparator());

        for (String targetUrl : this.urlPdfs) {
            if (targetUrl == null || targetUrl.isEmpty()) {
                continue;
            }
            try {
                disableSSLCertificateVerification();
                Path primaryPdfPath = manualDir.resolve(targetUrl.replaceAll("[:/]", "-"));
                downloadPdf(targetUrl, primaryPdfPath.toString());
                logger.info("Successfully downloaded PDF from {} to {}", targetUrl, primaryPdfPath);

                domains.addAll(extractDomainsFromPDF(primaryPdfPath.toString()));
                if (debug) {
                    logger.debug("Extracted {} domains from PDF", domains.size());
                }

            } catch (Exception e) {
                logger.error("Error parsing aggressor services: {}", e.getMessage(), e);
            }
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

    private void downloadPdf(String pdfUrl, String destinationPath) throws
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

    private Set<BlockedDomain> extractDomainsFromPDF(String filePath) {
        Set<BlockedDomain> domains = new TreeSet<>(new BlockedDomainComparator());

        try {
            File file = new File(filePath);
            try (PDDocument document = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
//                String cleanedText = text.replaceAll("\n", "").replaceAll("[0-9]+ .http", " http");
                String cleanedText = text.replaceAll("[0-9]+\\s+\\.http", " http");
                logger.info("Document: {}", cleanedText);

                // ліцензії1. https://1bitstarz.com2. https://1slotik.com3. https://21bitstarz.com4. https://22bitstarz.com5. https://23bitstarz.com6. https://24bitstarz.com7. https://26bitstarz.com8. https://2bitstarz.com9. https://49-joker-casino.com10.https://https://bc.game11.https://bc-game.in12.https://bcgame-ua.com13.https://casino-elslots.com.ua14.https://casino-joker-58.com15.https://casino-joker-win.com.ua16.https://casino-vip.net.ua17.https://casinovip.net.ua18.https://coinpoker.com19.https://dripcasino121.com20.https://elslotscasino.org.ua21.https://elslots.in.ua22.https://elslotss-inside.top23.https://elslts-volatility.top24.https://fresh452.casino25.https://freshcasino3026.com26.https://freshcasino3028.com27.https://funelslot.com28.https://gizbo-casino8.com29.https://goxbet29.com30.https://jet255.casino31.https://jet.casino232.https://jetcasino10017.com33.https://jetcasino10018.com34.https://jetcasino.com.ua35.https://jetcasino.in.ua36.https://johnny24casino.com37.https://johnny-24.net38.https://johnny-online.com39.https://joker-poker.com.ua40.https://joker-win1.com41.https://legzocasino10012.com42.https://melbet303.com43.https://melbet624.top44.https://monoslot15.com45.https://monro80.casino46.https://monrocasino607.com47.https://monro-casino.biz48.https://mostbet-ua5.com49.https://parik2412.com.ua50.https://parik2414.com.ua51.https://parik247.com.ua52.https://parik24-casino.com.ua53.https://parik-24.com.ua54.https://parik-24.info55.https://parik24-play.com.ua56.https://parik24.site57.https://parik24.store58.https://pointloto20.com59.https://primeelslotss.com60.https://rubet.com61.https://safeteams.co62.https://slotclub1.pro63.https://sports.mtt.xyz64.https://stake1021.com65.https://stake1039.com66.https://stardacasinoua11.com67.https://stardacasinoua16.com68.https://stardacasinoua17.com69.https://stardacasinoua18.com370.https://stardacasinoua19.com71.https://vipkazinothemedslots.top72.https://vippcasinortp.top73.https://volna-casino10044.com74.https://volna-casino10046.com75.https://xboct.org76.https://yumas.com.ua
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

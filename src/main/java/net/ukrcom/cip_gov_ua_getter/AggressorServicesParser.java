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
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Парсер для отримання списку доменів із сервісів держави-агресора.
 *
 * @author olden
 */
public class AggressorServicesParser extends PDFParser {

    private final String primaryPdfName;

    public AggressorServicesParser(Properties properties, boolean debug) {
        super(properties, debug);
        this.sourceDomain = properties.getProperty("AggressorServices_SOURCE_DOMAIN", "webportal.nrada.gov.ua");
        this.primaryPdfName = properties.getProperty("AggressorServices_PRIMARY_PDF_NAME", "Perelik.#450.2023.07.06.pdf");
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
            disableSSLCertificateVerification();
            String pdfUrl = findPdfUrl(targetUrl);
            if (pdfUrl != null) {
                Path primaryPdfPath = manualDir.resolve(primaryPdfName);
                downloadPdf(pdfUrl, primaryPdfPath.toString());
                logger.info("Successfully downloaded PDF to: {}", primaryPdfPath);
                domains.addAll(extractDomainsFromPDF(primaryPdfPath.toString()));
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

}

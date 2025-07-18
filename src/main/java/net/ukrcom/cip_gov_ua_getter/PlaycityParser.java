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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Парсер для отримання списку доменів із сервісів держави-агресора.
 *
 * @author olden
 */
public class PlaycityParser extends AbstractPDFParser {

    private final String[] urlPdfs;

    public PlaycityParser(Properties properties, boolean debug) {
        super(properties, debug);
        this.sourceDomain = "nkek.gov.ua";
        this.urlPdfs = Arrays.stream(properties.getProperty("urlPdfs", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
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

    @Override
    public String prepareDocument(String text) {
        return text
                .replaceAll("\n", " ")
                .replaceAll("\\d+\\s*\\.\\s*http", " http")
                .replaceAll("\\s+", " ");
    }

}

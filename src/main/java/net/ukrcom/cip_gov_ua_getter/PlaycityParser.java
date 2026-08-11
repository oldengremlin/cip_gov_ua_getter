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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Парсер для отримання списку доменів «ПлейСіті» з PDF-файлів за списком
 * URL, указаних у властивості {@code urlPdfs}.
 *
 * @author olden
 */
public class PlaycityParser extends AbstractPDFParser {

    private final String[] urlPdfs;

    public PlaycityParser(Properties properties, boolean debug) {
        super(properties, debug);
        this.sourceDomain = "nkek.gov.ua";
        List<String> resolvedUrls = new ArrayList<>();
        String rawPdfs = properties.getProperty("urlPdfs", "");
        for (String entry : rawPdfs.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("file:")) {
                String filePath = trimmed.substring(5);
                if (filePath.startsWith("~")) {
                    filePath = System.getProperty("user.home") + filePath.substring(1);
                }
                Path p = Path.of(filePath);
                if (!Files.isReadable(p)) {
                    logger.warn("urlPdfs file not found or not readable: {}", p);
                    continue;
                }
                try {
                    for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        String trimmedLine = line.trim();
                        if (!trimmedLine.isEmpty() && !trimmedLine.startsWith("#")) {
                            resolvedUrls.add(trimmedLine);
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Failed to read urlPdfs file {}: {}", p, e.getMessage());
                }
            } else {
                resolvedUrls.add(trimmed);
            }
        }
        this.urlPdfs = resolvedUrls.toArray(String[]::new);
    }

    @Override
    public Set<BlockedDomain> parse() {
        // LinkedHashMap зберігає порядок зі списку — зручніше читати лог.
        // Дублікати URL природно згортаються в один запис.
        Map<String, Path> targets = new LinkedHashMap<>();

        for (String targetUrl : this.urlPdfs) {
            if (targetUrl == null || targetUrl.isEmpty()) {
                continue;
            }
            try {
                URI uri = new URI(targetUrl);
                if (!uri.isAbsolute() || (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))) {
                    logger.warn("Skipping non-HTTP entry in urlPdfs: {}", targetUrl);
                    continue;
                }
            } catch (URISyntaxException e) {
                logger.warn("Skipping malformed URL in urlPdfs: {}: {}", targetUrl, e.getMessage());
                continue;
            }
            String rawName = targetUrl.replaceAll("[:/]", "-");
            if (rawName.length() > 200) {
                rawName = rawName.substring(rawName.length() - 200);
            }
            Path pdfPath = manualDir.resolve(rawName);

            // Обрізання імені до 200 символів теоретично може зрівняти два різних
            // URL. Послідовно це давало б лише зайве перезавантаження, а
            // паралельно — два потоки в один тимчасовий файл. Тому пропускаємо.
            if (targets.containsValue(pdfPath)) {
                logger.warn("Skipping URL whose cache file name collides with an earlier one: {}", targetUrl);
                continue;
            }
            if (targets.putIfAbsent(targetUrl, pdfPath) != null) {
                logger.debug("Duplicate URL in urlPdfs, skipping: {}", targetUrl);
            }
        }

        // Кожен URL тут — окремий, незмінний документ, а не «поточний стан»,
        // що перевидається під новим іменем: локальне ім'я кешу похідне від
        // самого URL, тож нове рішення природно завантажується під новим
        // файлом. Кешування назавжди (maxAge = null) тут коректне — на
        // відміну від переліку сервісів держави-агресора, перевіряти вік не
        // потрібно.
        return downloadAndExtractAll(targets, null);
    }

    @Override
    public String prepareDocument(String text) {
        return text
                .replaceAll("\n", " ")
                .replaceAll("\\d+\\s*\\.\\s*http", " http")
                .replaceAll("\\s+", " ");
    }

}

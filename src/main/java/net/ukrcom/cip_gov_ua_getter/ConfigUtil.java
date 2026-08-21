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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Спільний розбір конфігурації для класів, що створюються багато разів за
 * запуск.
 * <p>
 * {@link GetPrescript} конструюється на кожне вкладення — сотні разів, — і
 * раніше кожен екземпляр наново розбирав {@code SERVICE_SUBDOMAINS} з
 * перевіркою регексом кожного елемента, наново парсив
 * {@code max_file_size_bytes} і наново викликав {@code createDirectories()}.
 * Тут результати кешуються за вхідним рядком, тож робота виконується один
 * раз. Заразом усунено дублювання: розбір {@code SERVICE_SUBDOMAINS} був
 * скопійований в {@link GetPrescript} і {@link AbstractPDFParser}.
 *
 * @author olden
 */
public final class ConfigUtil {

    private static final Logger logger = LoggerFactory.getLogger(ConfigUtil.class);

    private static final String DEFAULT_SUBDOMAINS
            = "www,ftp,mail,api,blog,shop,login,admin,web,secure,m,mobile,app,dev,test,m";

    private static final ConcurrentHashMap<String, String[]> SUBDOMAIN_CACHE = new ConcurrentHashMap<>();
    private static final Set<Path> ENSURED_DIRS = ConcurrentHashMap.newKeySet();

    private ConfigUtil() {
    }

    /**
     * Повертає перелік службових субдоменів, які прибираються з початку
     * доменного імені. Результат кешується — масив спільний, змінювати його
     * не можна.
     *
     * @param properties властивості
     * @return непорожній (за звичайних налаштувань) масив субдоменів
     */
    public static String[] serviceSubdomains(Properties properties) {
        String raw = properties.getProperty("SERVICE_SUBDOMAINS", DEFAULT_SUBDOMAINS);
        return SUBDOMAIN_CACHE.computeIfAbsent(raw, ConfigUtil::parseSubdomains);
    }

    private static String[] parseSubdomains(String raw) {
        String[] result = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> {
                    boolean valid = s.matches("[a-zA-Z0-9-]+");
                    if (!valid) {
                        logger.debug("Invalid subdomain skipped: {}", s);
                    }
                    return valid;
                })
                .distinct()
                .toArray(String[]::new);
        if (result.length == 0) {
            logger.warn("No valid service subdomains defined in SERVICE_SUBDOMAINS");
        }
        return result;
    }

    /**
     * Нормалізує шлях і створює директорію, якщо її ще немає. Повторні
     * виклики для того самого шляху не звертаються до файлової системи.
     *
     * @param path шлях до директорії
     * @return нормалізований шлях
     */
    public static Path ensureDirectory(String path) {
        Path dir = Paths.get(path.trim()).normalize();
        if (ENSURED_DIRS.contains(dir)) {
            return dir;
        }
        try {
            Files.createDirectories(dir);
            ENSURED_DIRS.add(dir);
            logger.debug("Ensured directory exists: {}", dir);
        } catch (IOException e) {
            logger.error("Failed to create directory {}: {}", dir, e.getMessage(), e);
            throw new RuntimeException("Cannot create directory: " + dir, e);
        }
        return dir;
    }

    /**
     * Читає додатне ціле значення властивості. Некоректне значення не має
     * зупиняти запуск — фолбек на типове з попередженням у лог.
     *
     * @param properties властивості
     * @param key назва властивості
     * @param defaultValue значення за замовчуванням
     * @return розібране значення або {@code defaultValue}
     */
    public static long positiveLong(Properties properties, String key, long defaultValue) {
        String raw = properties.getProperty(key, String.valueOf(defaultValue)).trim();
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                logger.warn("{} must be positive, using default: {}", key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException e) {
            logger.warn("Invalid {} value '{}', using default: {}", key, raw, defaultValue);
            return defaultValue;
        }
    }
}

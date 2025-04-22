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
import org.slf4j.Logger;

import java.net.IDN;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DomainValidatorUtil {

    private static final ConcurrentHashMap<String, String> SKELETON_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> TLD_CACHE = new ConcurrentHashMap<>();
    // Регулярний вираз для виділення валідних доменів, включаючи повну підтримку Unicode і подвійних дефісів
    /*
        Розбір DOMAIN_CLEAN_PATTERN
        1. [a-zA-Z0-9\\p{L}\\p{M}*-]+
           - Матчує першу частину домену (до крапки), дозволяючи дефіси.
           - [a-zA-Z0-9\\p{L}\\p{M}*-]: Символьний клас:
             - a-zA-Z: Латинські літери.
             - 0-9: Цифри.
             - \\p{L}: Unicode-літери (кирилиця, китайські ієрогліфи тощо).
             - \\p{M}*: Нуль або більше діакритичних знаків.
             - -: Дефіс (дозволяє --).
           - +: Один або більше символів.
           - Приклад: nasepravda, xn--b1akbpgy3fwa, приклад.

        2. (?:\\.[a-zA-Z0-9\\p{L}\\p{M}*-]+)+
           - Матчує одну або більше частин після крапки (TLD або багаторівневий домен).
           - \\.: Буквальна крапка.
           - [a-zA-Z0-9\\p{L}\\p{M}*-]+: Символьний клас, як вище.
           - +: Одна або більше груп із крапкою.
           - Приклад: .cz, .xn--p1acf, .co.uk.
     */
    private static final Pattern DOMAIN_CLEAN_PATTERN = Pattern.compile(
            "[a-zA-Z0-9\\p{L}\\p{M}*-]+"
            + "(?:\\.[a-zA-Z0-9\\p{L}\\p{M}*-]+)+"
    );

    public static Set<String> validateDomain(String rawDomain, String[] serviceSubdomains, String sourceDomain,
            DomainValidator domainValidator, InetAddressValidator ipValidator,
            SpoofChecker spoofChecker, Logger logger, boolean includeBlockedDomain,
            LocalDateTime dateTime, Set<BlockedDomain> blockedDomains) {
        Set<String> validDomains = new HashSet<>();

        try {
            // Очищаємо вхідну строку від протоколів і пробілів
            String cleanedInput = rawDomain
                    .trim()
                    .replaceAll("(?i)^(https?://|ftp://)", "") // Видаляємо протоколи
                    .replaceAll("\\s+", "") // Видаляємо пробіли
                    .toLowerCase();

            // Витягуємо всі валідні домени зі строки
            Matcher matcher = DOMAIN_CLEAN_PATTERN.matcher(cleanedInput);
            boolean found = false;

            while (matcher.find()) {
                found = true;
                String domain = matcher.group();
                logger.debug("Cleaned domain: {} → {}", rawDomain, domain);

                // Перевірка на порожній домен або надмірну довжину
                if (domain.isBlank() || domain.length() > 255) {
                    logger.warn("Skipping domain due to invalid length: {}", domain);
                    continue;
                }

                // Видаляємо субдомени зі списку serviceSubdomains
                for (String service : serviceSubdomains) {
                    if (domain.startsWith(service + ".")) {
                        domain = domain.substring(service.length() + 1);
                        break;
                    }
                }

                // Видаляємо шляхи, порти, параметри
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

                // Пропускаємо sourceDomain, якщо він є
                if (sourceDomain != null && domain.equals(sourceDomain)) {
                    logger.warn("Skipping source domain: {}", domain);
                    continue;
                }

                // Конвертуємо в Punycode
                String idnDomain = IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED);
                if (idnDomain.length() > 255) {
                    logger.warn("Skipping domain after IDN conversion due to length: {}", idnDomain);
                    continue;
                }

                // Перевіряємо валідність IDN-домену
                if (domainValidator.isValid(idnDomain)) {
                    String tld = extractTld(idnDomain);
                    if (tld == null) {
                        logger.warn("Invalid TLD (null) for domain: {}", idnDomain);
                        continue;
                    }
                    // Перевіряємо TLD через кеш
                    Boolean isValidTld = TLD_CACHE.computeIfAbsent(tld, k -> domainValidator.isValidTld(k));
                    if (!isValidTld) {
                        logger.warn("Invalid TLD '{}' for domain: {}", tld, idnDomain);
                        continue;
                    }
                    validDomains.add(idnDomain);
                    if (includeBlockedDomain) {
                        blockedDomains.add(new BlockedDomain(idnDomain, true, dateTime));
                    }
                    logger.info("Valid IDN domain: {}", idnDomain);
                } else if (ipValidator.isValid(domain)) {
                    logger.warn("Skipping IP address: {}", domain);
                    continue;
                } else {
                    logger.warn("Invalid IDN domain: {}", domain);
                }

                // Обробка гомогліфів для нелатинських символів
                boolean hasNonLatin = domain.chars().anyMatch(c -> c > 127);
                if (hasNonLatin) {
                    String latinized = SKELETON_CACHE.computeIfAbsent(domain, spoofChecker::getSkeleton);
                    String latinizedIdn = IDN.toASCII(latinized, IDN.ALLOW_UNASSIGNED).toLowerCase();
                    if (latinizedIdn.length() > 255) {
                        logger.warn("Skipping latinized domain due to length: {}", latinizedIdn);
                    } else if (domainValidator.isValid(latinizedIdn) && !latinizedIdn.equals(idnDomain)) {
                        String latinizedTld = extractTld(latinizedIdn);
                        if (latinizedTld == null) {
                            logger.warn("Invalid TLD (null) for latinized domain: {}", latinizedIdn);
                            continue;
                        }
                        // Перевіряємо TLD через кеш для латинізованого домену
                        Boolean isValidLatinizedTld = TLD_CACHE.computeIfAbsent(latinizedTld, k -> domainValidator.isValidTld(k));
                        if (!isValidLatinizedTld) {
                            logger.warn("Invalid TLD '{}' for latinized domain: {}", latinizedTld, latinizedIdn);
                            continue;
                        }
                        validDomains.add(latinizedIdn);
                        if (includeBlockedDomain) {
                            blockedDomains.add(new BlockedDomain(latinizedIdn, true, dateTime));
                        }
                        logger.info("Valid latinized domain: {} (from {} ⮕ {})", latinizedIdn, domain, latinized);
                    } else {
                        logger.debug("Latinized domain invalid or identical: {} (from {} ⮕ {})", latinized, domain, latinized);
                    }
                }
            }

            if (!found) {
                logger.warn("No valid domain found in: {}", rawDomain);
            }

        } catch (Exception e) {
            logger.warn("Error processing domain {}: {}", rawDomain, e.getMessage());
        }

        return validDomains;
    }

    private static String extractTld(String domain) {
        if (domain == null || domain.isEmpty()) {
            return null;
        }
        int lastDot = domain.lastIndexOf('.');
        if (lastDot == -1 || lastDot == domain.length() - 1) {
            return null;
        }
        return domain.substring(lastDot);
    }
}

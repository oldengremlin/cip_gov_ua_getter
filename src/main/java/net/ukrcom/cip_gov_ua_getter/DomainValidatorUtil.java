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

public class DomainValidatorUtil {

    private static final ConcurrentHashMap<String, String> SKELETON_CACHE = new ConcurrentHashMap<>();

    public static Set<String> validateDomain(String rawDomain, String[] serviceSubdomains, String sourceDomain,
            DomainValidator domainValidator, InetAddressValidator ipValidator,
            SpoofChecker spoofChecker, Logger logger, boolean includeBlockedDomain,
            LocalDateTime dateTime, Set<BlockedDomain> blockedDomains) {
        Set<String> validDomains = new HashSet<>();

        try {
            String domain = rawDomain
                    .trim()
                    .replaceAll("(?i)^(https?://|ftp://)", "")
                    .replaceAll("\\s+", "")
                    .toLowerCase();

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

            if (domain.isBlank() || domain.length() > 255) {
                logger.warn("Skipping domain due to invalid length: {}", domain);
                return validDomains;
            }

            if (sourceDomain != null && domain.equals(sourceDomain)) {
                logger.warn("Skipping source domain: {}", domain);
                return validDomains;
            }

            String idnDomain = IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED);
            if (idnDomain.length() > 255) {
                logger.warn("Skipping domain after IDN conversion due to length: {}", idnDomain);
                return validDomains;
            }

            if (domainValidator.isValid(idnDomain)) {
                String tld = extractTld(idnDomain);
                if (tld == null || !domainValidator.isValidTld(tld)) {
                    logger.warn("Invalid TLD '{}' for domain: {}", tld, idnDomain);
                    return validDomains;
                }
                validDomains.add(idnDomain);
                if (includeBlockedDomain) {
                    blockedDomains.add(new BlockedDomain(idnDomain, true, dateTime));
                }
                logger.info("Valid IDN domain: {}", idnDomain);
            } else if (ipValidator.isValid(domain)) {
                logger.warn("Skipping IP address: {}", domain);
                return validDomains;
            } else {
                logger.warn("Invalid IDN domain: {}", domain);
            }

            boolean hasNonLatin = domain.chars().anyMatch(c -> c > 127);
            if (hasNonLatin) {
                String latinized = SKELETON_CACHE.computeIfAbsent(domain, spoofChecker::getSkeleton);
                String latinizedIdn = IDN.toASCII(latinized, IDN.ALLOW_UNASSIGNED).toLowerCase();
                if (latinizedIdn.length() > 255) {
                    logger.warn("Skipping latinized domain due to length: {}", latinizedIdn);
                } else if (domainValidator.isValid(latinizedIdn) && !latinizedIdn.equals(idnDomain)) {
                    String latinizedTld = extractTld(latinizedIdn);
                    if (latinizedTld == null || !domainValidator.isValidTld(latinizedTld)) {
                        logger.warn("Invalid TLD '{}' for latinized domain: {}", latinizedTld, latinizedIdn);
                    } else {
                        validDomains.add(latinizedIdn);
                        if (includeBlockedDomain) {
                            blockedDomains.add(new BlockedDomain(latinizedIdn, true, dateTime));
                        }
                        logger.info("Valid latinized domain: {} (from {} ⮕ {})", latinizedIdn, domain, latinized);
                    }
                } else {
                    logger.debug("Latinized domain invalid or identical: {} (from {} ⮕ {})", latinized, domain, latinized);
                }
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

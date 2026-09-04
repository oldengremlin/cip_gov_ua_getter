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

import com.google.common.net.InternetDomainName;
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
        1. [a-zA-Z0-9\\p{L}\\p{M}-]+
           - Матчує першу частину домену (до крапки), дозволяючи дефіси.
           - [a-zA-Z0-9\\p{L}\\p{M}-]: Символьний клас:
             - a-zA-Z: Латинські літери.
             - 0-9: Цифри.
             - \\p{L}: Unicode-літери (кирилиця, китайські ієрогліфи тощо).
             - \\p{M}: Діакритичні знаки (комбіновані символи).
             - -: Дефіс (дозволяє --).
           - +: Один або більше символів.
           - Приклад: nasepravda, xn--b1akbpgy3fwa, приклад.

        2. (?:\\.[a-zA-Z0-9\\p{L}\\p{M}-]+)+
           - Матчує одну або більше частин після крапки (TLD або багаторівневий домен).
           - \\.: Буквальна крапка.
           - [a-zA-Z0-9\\p{L}\\p{M}-]+: Символьний клас, як вище.
           - +: Одна або більше груп із крапкою.
           - Приклад: .cz, .xn--p1acf, .co.uk.

        Увага: усередині символьного класу [...] зірочка є літералом, а не
        квантифікатором. Тому "*" тут навмисно відсутня — інакше шаблон
        приймав би імена на кшталт "foo*bar.com".
     */
    private static final Pattern DOMAIN_CLEAN_PATTERN = Pattern.compile(
            "[a-zA-Z0-9\\p{L}\\p{M}-]+"
            + "(?:\\.[a-zA-Z0-9\\p{L}\\p{M}-]+)+"
    );

    /**
     * Хвіст, що вказує на злипання з наступним посиланням у тексті PDF.
     * {@code prepareDocument} прибирає переноси рядків, тож коли один запис
     * без схеми ({@code hd.muvee.me}) стоїть у джерелі одразу перед іншим,
     * що починається зі схеми ({@code https://...}), вони склеюються в
     * {@code hd.muvee.mehttps}, і жадібна група TLD у "голому" домені
     * захоплює зайве. {@code htpps} — підтверджений на практиці варіант
     * {@code https} із переставленими літерами (артефакт вбудованого
     * шрифту при екстракції тексту з PDF, не власне помилка джерела).
     */
    private static final Pattern GLUED_SCHEME_SUFFIX = Pattern.compile("(?i)(https|http|ftp|htpps)$");

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

                // Видаляємо шляхи, порти, параметри — до зрізання субдомену,
                // щоб перевірка на публічний суфікс бачила чисте ім'я
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

                // Видаляємо службовий субдомен — але лише якщо після цього
                // лишиться реєстрований домен. Інакше www.com.ua перетворився б
                // на com.ua, а це публічний суфікс: заблокувавши його на DNS,
                // ми поклали б усю зону. Такі імена (shop.com.ua, test.com.ua)
                // цілком реєстровані й можуть бути законною ціллю блокування,
                // тому лишаємо їх як є.
                for (String service : serviceSubdomains) {
                    if (domain.startsWith(service + ".")) {
                        String remainder = domain.substring(service.length() + 1);
                        if (isUnderPublicSuffix(remainder)) {
                            domain = remainder;
                        } else {
                            logger.debug("Keeping {} as is: stripping '{}' would leave public suffix '{}'",
                                    domain, service, remainder);
                        }
                        break;
                    }
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

                // Останній запобіжник: публічний суфікс не може бути ціллю
                // блокування — це не сайт, а ціла зона (com.ua, kiev.ua, co.uk).
                if (isPublicSuffix(idnDomain)) {
                    logger.warn("Refusing to block a public suffix: {} (from {})", idnDomain, rawDomain);
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
                    // Евристичний фолбек — лише після невдачі звичайної
                    // валідації, тож справді биті фрагменти без схеми в
                    // хвості (77.muvee) сюди не потрапляють і лишаються
                    // Invalid, як і мають.
                    String healedIdn = healGluedSchemeSuffix(domain, domainValidator);
                    if (healedIdn != null) {
                        validDomains.add(healedIdn);
                        if (includeBlockedDomain) {
                            blockedDomains.add(new BlockedDomain(healedIdn, true, dateTime));
                        }
                        logger.info("Valid IDN domain: {} (heuristically recovered from '{}' — "
                                + "PDF text likely glued to the next URL without a separator)",
                                healedIdn, domain);
                    } else {
                        logger.warn("Invalid IDN domain: {}", domain);
                    }
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

    /**
     * Чи є ім'я публічним суфіксом (зоною, під якою реєструють домени) —
     * наприклад {@code com.ua}, {@code kiev.ua}, {@code co.uk}, {@code com}.
     *
     * @param domain доменне ім'я
     * @return true, якщо це публічний суфікс
     */
    private static boolean isPublicSuffix(String domain) {
        try {
            return InternetDomainName.from(domain).isPublicSuffix();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Чи є ім'я реєстрованим доменом під публічним суфіксом — тобто чи має
     * воно власну «приватну» частину ({@code shop.com.ua} має, {@code com.ua}
     * не має).
     *
     * @param domain доменне ім'я
     * @return true, якщо під публічним суфіксом є хоча б один власний лейбл
     */
    private static boolean isUnderPublicSuffix(String domain) {
        try {
            return InternetDomainName.from(domain).isUnderPublicSuffix();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Пробує «вилікувати» домен, чий кінець злипся зі схемою наступного
     * посилання ({@code hd.muvee.mehttps} → {@code hd.muvee.me}).
     * <p>
     * Викликається лише як фолбек, коли домен уже не пройшов звичайну
     * валідацію: спершу відрізає хвіст {@link #GLUED_SCHEME_SUFFIX}, потім
     * заново проганяє результат через ту саму перевірку (публічний суфікс,
     * IDN, TLD), що й основний шлях. Якщо після відрізання лишається
     * порожній рядок або лишок не закінчується літерою чи цифрою — не
     * ризикуємо й повертаємо {@code null}: фрагмент типу {@code 77.muvee}
     * (без приліпленої схеми) сюди взагалі не потрапляє, а щось на кшталт
     * випадково відрізаного до дефіса теж не приймається.
     *
     * @param domain домен, що не пройшов звичайну валідацію
     * @param domainValidator валідатор доменів
     * @return вилікуваний Punycode-домен, якщо він валідний без хвоста;
     * інакше {@code null}
     */
    private static String healGluedSchemeSuffix(String domain, DomainValidator domainValidator) {
        Matcher m = GLUED_SCHEME_SUFFIX.matcher(domain);
        if (!m.find()) {
            return null;
        }
        String stripped = domain.substring(0, m.start());
        if (stripped.isEmpty() || !Character.isLetterOrDigit(stripped.charAt(stripped.length() - 1))) {
            return null;
        }
        try {
            String strippedIdn = IDN.toASCII(stripped, IDN.ALLOW_UNASSIGNED);
            if (strippedIdn.length() > 255 || isPublicSuffix(strippedIdn) || !domainValidator.isValid(strippedIdn)) {
                return null;
            }
            String tld = extractTld(strippedIdn);
            if (tld == null) {
                return null;
            }
            Boolean isValidTld = TLD_CACHE.computeIfAbsent(tld, k -> domainValidator.isValidTld(k));
            return Boolean.TRUE.equals(isValidTld) ? strippedIdn : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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

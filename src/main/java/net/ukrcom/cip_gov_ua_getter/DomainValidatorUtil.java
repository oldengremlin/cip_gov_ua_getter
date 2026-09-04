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

    /**
     * Ознака номера рядка таблиці, зжованого в середину домену. У «Переліках»
     * НЦУ/НКЕК кожен сервіс — рядок таблиці з номером ({@code 13.}), і коли
     * останній URL одного рядка після видалення переносів злипається з
     * номером наступного ({@code kinogo.ga} + {@code 13.} + {@code Kinokrad}
     * → {@code kinogo.ga13.kinokrad}), жадібна група TLD захоплює й номер, і
     * початок наступної назви. Довжину пробігу цифр навмисно не обмежуємо
     * ({@code \d+}, а не {@code \d{1,3}}): перелік теоретично може вирости
     * за 999 позицій, а обмеження просто мовчки перестало б спрацьовувати
     * без жодного попередження — тоді як зайвий ризик тут відсутній,
     * оскільки кандидат однаково проходить повну повторну валідацію.
     */
    private static final Pattern EMBEDDED_ROW_NUMBER = Pattern.compile("\\d+\\.");

    public static Set<String> validateDomain(String rawDomain, String[] serviceSubdomains, String sourceDomain,
            DomainValidator domainValidator, InetAddressValidator ipValidator,
            SpoofChecker spoofChecker, Logger logger, boolean includeBlockedDomain,
            LocalDateTime dateTime, Set<BlockedDomain> blockedDomains) {
        return validateDomain(rawDomain, serviceSubdomains, sourceDomain, domainValidator, ipValidator,
                spoofChecker, logger, includeBlockedDomain, dateTime, blockedDomains, false);
    }

    /**
     * @param quiet якщо {@code true} — усі повідомлення, що звичайно йдуть на
     * рівень {@code INFO}/{@code WARN}, пишуться на {@code DEBUG}. Потрібно
     * дорадчому проходу ({@code AbstractPDFParser.extractDiagnosticNames}):
     * він проганяє через цей самий метод увесь текст документа вдруге, з
     * іншою стратегією об'єднання рядків, і без цього прапорця подвоював би
     * обсяг INFO/WARN-логів на кожен запуск, не додаючи користі — бо
     * авторитетний прохід уже залогував ті самі домени.
     */
    public static Set<String> validateDomain(String rawDomain, String[] serviceSubdomains, String sourceDomain,
            DomainValidator domainValidator, InetAddressValidator ipValidator,
            SpoofChecker spoofChecker, Logger logger, boolean includeBlockedDomain,
            LocalDateTime dateTime, Set<BlockedDomain> blockedDomains, boolean quiet) {
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
                    logWarn(logger, quiet, "Skipping domain due to invalid length: {}", domain);
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

                domain = stripServiceSubdomain(domain, serviceSubdomains, logger);

                // Пропускаємо sourceDomain, якщо він є
                if (sourceDomain != null && domain.equals(sourceDomain)) {
                    logWarn(logger, quiet, "Skipping source domain: {}", domain);
                    continue;
                }

                // Конвертуємо в Punycode
                String idnDomain = IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED);
                if (idnDomain.length() > 255) {
                    logWarn(logger, quiet, "Skipping domain after IDN conversion due to length: {}", idnDomain);
                    continue;
                }

                // Останній запобіжник: публічний суфікс не може бути ціллю
                // блокування — це не сайт, а ціла зона (com.ua, kiev.ua, co.uk).
                if (isPublicSuffix(idnDomain)) {
                    logWarn(logger, quiet, "Refusing to block a public suffix: {} (from {})", idnDomain, rawDomain);
                    continue;
                }

                // Перевіряємо валідність IDN-домену
                if (domainValidator.isValid(idnDomain)) {
                    String tld = extractTld(idnDomain);
                    if (tld == null) {
                        logWarn(logger, quiet, "Invalid TLD (null) for domain: {}", idnDomain);
                        continue;
                    }
                    // Перевіряємо TLD через кеш
                    Boolean isValidTld = TLD_CACHE.computeIfAbsent(tld, k -> domainValidator.isValidTld(k));
                    if (!isValidTld) {
                        logWarn(logger, quiet, "Invalid TLD '{}' for domain: {}", tld, idnDomain);
                        continue;
                    }
                    validDomains.add(idnDomain);
                    if (includeBlockedDomain) {
                        blockedDomains.add(new BlockedDomain(idnDomain, true, dateTime));
                    }
                    logInfo(logger, quiet, "Valid IDN domain: {}", idnDomain);
                } else if (ipValidator.isValid(domain)) {
                    logWarn(logger, quiet, "Skipping IP address: {}", domain);
                    continue;
                } else {
                    // Евристичні фолбеки — лише після невдачі звичайної
                    // валідації, тож справді биті фрагменти без приліпленого
                    // хвоста (77.muvee) сюди не потрапляють і лишаються
                    // Invalid, як і мають. Обидва пишуть однаковий за формою
                    // лог ("heuristically recovered from"), щоб один grep
                    // ловив усі евристичні відновлення разом.
                    String healedIdn = healGluedSchemeSuffix(domain, domainValidator, serviceSubdomains, logger);
                    String healedFrom = "the next URL's scheme, with no separator";
                    if (healedIdn == null) {
                        healedIdn = healEmbeddedRowNumber(domain, domainValidator, serviceSubdomains, logger);
                        healedFrom = "the next table row's number, with no separator";
                    }
                    if (healedIdn != null) {
                        validDomains.add(healedIdn);
                        if (includeBlockedDomain) {
                            blockedDomains.add(new BlockedDomain(healedIdn, true, dateTime));
                        }
                        logInfo(logger, quiet, "Valid IDN domain: {} (heuristically recovered from '{}' — "
                                + "PDF text likely glued to {})",
                                healedIdn, domain, healedFrom);
                    } else {
                        logWarn(logger, quiet, "Invalid IDN domain: {}", domain);
                    }
                }

                // Обробка гомогліфів для нелатинських символів
                boolean hasNonLatin = domain.chars().anyMatch(c -> c > 127);
                if (hasNonLatin) {
                    String latinized = SKELETON_CACHE.computeIfAbsent(domain, spoofChecker::getSkeleton);
                    String latinizedIdn = IDN.toASCII(latinized, IDN.ALLOW_UNASSIGNED).toLowerCase();
                    if (latinizedIdn.length() > 255) {
                        logWarn(logger, quiet, "Skipping latinized domain due to length: {}", latinizedIdn);
                    } else if (domainValidator.isValid(latinizedIdn) && !latinizedIdn.equals(idnDomain)) {
                        String latinizedTld = extractTld(latinizedIdn);
                        if (latinizedTld == null) {
                            logWarn(logger, quiet, "Invalid TLD (null) for latinized domain: {}", latinizedIdn);
                            continue;
                        }
                        // Перевіряємо TLD через кеш для латинізованого домену
                        Boolean isValidLatinizedTld = TLD_CACHE.computeIfAbsent(latinizedTld, k -> domainValidator.isValidTld(k));
                        if (!isValidLatinizedTld) {
                            logWarn(logger, quiet, "Invalid TLD '{}' for latinized domain: {}", latinizedTld, latinizedIdn);
                            continue;
                        }
                        validDomains.add(latinizedIdn);
                        if (includeBlockedDomain) {
                            blockedDomains.add(new BlockedDomain(latinizedIdn, true, dateTime));
                        }
                        logInfo(logger, quiet, "Valid latinized domain: {} (from {} ⮕ {})", latinizedIdn, domain, latinized);
                    } else {
                        logger.debug("Latinized domain invalid or identical: {} (from {} ⮕ {})", latinized, domain, latinized);
                    }
                }
            }

            if (!found) {
                logWarn(logger, quiet, "No valid domain found in: {}", rawDomain);
            }

        } catch (Exception e) {
            logWarn(logger, quiet, "Error processing domain {}: {}", rawDomain, e.getMessage());
        }

        return validDomains;
    }

    private static void logInfo(Logger logger, boolean quiet, String format, Object... args) {
        if (quiet) {
            logger.debug(format, args);
        } else {
            logger.info(format, args);
        }
    }

    private static void logWarn(Logger logger, boolean quiet, String format, Object... args) {
        if (quiet) {
            logger.debug(format, args);
        } else {
            logger.warn(format, args);
        }
    }

    /**
     * Прибирає службовий субдомен ({@code www}, {@code ftp}, {@code mail} та
     * решту зі {@code SERVICE_SUBDOMAINS}) — але лише якщо після цього
     * лишиться реєстрований домен.
     * <p>
     * Інакше {@code www.com.ua} перетворився б на {@code com.ua}, а це
     * публічний суфікс: заблокувавши його на DNS, ми поклали б усю зону.
     * Такі імена ({@code shop.com.ua}, {@code test.com.ua}) цілком
     * реєстровані й можуть бути законною ціллю блокування, тому лишаються як
     * є.
     * <p>
     * Викликається двічі: у звичайному потоці й ще раз після евристичного
     * лікування. Другий виклик обов'язковий, бо в момент першого TLD ще
     * зіпсований склеюванням ({@code www.ivi.ruhtpps}), перевірка на
     * публічний суфікс не бачить реєстрованого домену й префікс лишається —
     * тож без повторного зрізання в блокування йшов би {@code www.ivi.ru}
     * замість зони {@code ivi.ru}, і то лише для склеєних імен, тоді як
     * звичайні зрізаються завжди.
     *
     * @param domain доменне ім'я
     * @param serviceSubdomains перелік службових префіксів
     * @param logger логер для пояснення, чому префікс лишено
     * @return ім'я без службового префікса або незмінене
     */
    private static String stripServiceSubdomain(String domain, String[] serviceSubdomains, Logger logger) {
        for (String service : serviceSubdomains) {
            if (domain.startsWith(service + ".")) {
                String remainder = domain.substring(service.length() + 1);
                if (isUnderPublicSuffix(remainder)) {
                    return remainder;
                }
                logger.debug("Keeping {} as is: stripping '{}' would leave public suffix '{}'",
                        domain, service, remainder);
                break;
            }
        }
        return domain;
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
     * @param serviceSubdomains службові префікси для повторного зрізання
     * @param logger логер
     * @return вилікуваний Punycode-домен, якщо він валідний без хвоста;
     * інакше {@code null}
     */
    private static String healGluedSchemeSuffix(String domain, DomainValidator domainValidator,
            String[] serviceSubdomains, Logger logger) {
        Matcher m = GLUED_SCHEME_SUFFIX.matcher(domain);
        if (!m.find()) {
            return null;
        }
        String stripped = domain.substring(0, m.start());
        if (stripped.isEmpty() || !Character.isLetterOrDigit(stripped.charAt(stripped.length() - 1))) {
            return null;
        }
        return asValidIdnOrNull(stripServiceSubdomain(stripped, serviceSubdomains, logger), domainValidator);
    }

    /**
     * Пробує «вилікувати» домен, зжований номером наступного рядка таблиці
     * ({@code kinogo.ga13.kinokrad} → {@code kinogo.ga}, бо {@code 13.} —
     * номер сусіднього рядка, а {@code Kinokrad} — початок його назви).
     * <p>
     * На відміну від {@link #healGluedSchemeSuffix}, тут кандидатів на
     * розрізання може бути декілька: справжні домени часто мають лейбли, що
     * закінчуються на цифру перед крапкою ({@code v4.tartugi.uno}), і перший
     * зліва пробіг цифр — не обов'язково той, що вказує на межу склеювання.
     * Тому перебираємо всі позиції зліва направо й лишаємо
     * <b>найдовший</b> префікс, що незалежно пройде повну валідацію: це і
     * найбезпечніший вибір (менше шансів випадково зупинитися на короткому
     * збігу), і найповніше відновлення.
     *
     * @param domain домен, що не пройшов звичайну валідацію
     * @param domainValidator валідатор доменів
     * @param serviceSubdomains службові префікси для повторного зрізання
     * @param logger логер
     * @return вилікуваний Punycode-домен або {@code null}
     */
    private static String healEmbeddedRowNumber(String domain, DomainValidator domainValidator,
            String[] serviceSubdomains, Logger logger) {
        Matcher m = EMBEDDED_ROW_NUMBER.matcher(domain);
        String best = null;
        while (m.find()) {
            if (m.start() == 0) {
                // Номер стоїть на самому початку (77.muvee) — префікса
                // немає, це не склеювання, а чистий шум номера рядка.
                continue;
            }
            String candidate = asValidIdnOrNull(
                    stripServiceSubdomain(domain.substring(0, m.start()), serviceSubdomains, logger),
                    domainValidator);
            if (candidate != null) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Спільна перевірка кандидата для обох евристичних фолбеків вище:
     * IDN-конверсія, публічний суфікс, валідність, TLD. Виокремлено, щоб не
     * дублювати ту саму послідовність перевірок двічі.
     *
     * @param candidate рядок-кандидат без протоколу й хвостового сміття
     * @param domainValidator валідатор доменів
     * @return Punycode-домен, якщо кандидат валідний; інакше {@code null}
     */
    private static String asValidIdnOrNull(String candidate, DomainValidator domainValidator) {
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            String idn = IDN.toASCII(candidate, IDN.ALLOW_UNASSIGNED);
            if (idn.length() > 255 || isPublicSuffix(idn) || !domainValidator.isValid(idn)) {
                return null;
            }
            String tld = extractTld(idn);
            if (tld == null) {
                return null;
            }
            Boolean isValidTld = TLD_CACHE.computeIfAbsent(tld, k -> domainValidator.isValidTld(k));
            return Boolean.TRUE.equals(isValidTld) ? idn : null;
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

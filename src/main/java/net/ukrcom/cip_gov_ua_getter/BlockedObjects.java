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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.commons.validator.routines.DomainValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клас формує перелік доменів, що підлягають блокуванню.
 *
 * @author olden
 */
public class BlockedObjects {

    private static final Logger logger = LoggerFactory.getLogger(BlockedObjects.class);

    private final String[] blockedNames;
    private final String blockedResultName;
    private final String[] serviceSubdomains;
    private final TreeSet<BlockedDomain> blockedDomains;
    private int normalizedCount = 0;
    private int refusedCount = 0;

    /**
     * Конструктор класа.
     *
     * @param p - об'єкт властивостей
     */
    public BlockedObjects(Properties p) {
        this.blockedNames = p.getProperty("blocked", "blocked.txt").split(";");
        this.blockedResultName = p.getProperty("blocked_result", "blocked.result.txt");
        this.serviceSubdomains = ConfigUtil.serviceSubdomains(p);
        this.blockedDomains = new TreeSet<>(new BlockedDomainComparator());
    }

    /**
     * Зчитує перелік доменів із файлів, указаних у властивості blocked. Додає
     * їх до TreeSet із датою за замовчуванням (початок епохи).
     *
     * @return цей об'єкт для ланцюгових викликів
     * @throws IOException у разі помилок читання файлів
     */
    public BlockedObjects getBlockedDomainNames() throws IOException {
        DomainValidator domainValidator = DomainValidator.getInstance(true);
        for (String blockedName : blockedNames) {
            File blockedFile = new File(blockedName.trim());
            if (!blockedFile.exists() || !blockedFile.isFile() || !blockedFile.canRead()) {
                logger.warn("File {} does not exist or is not readable", blockedName);
                continue;
            }
            logger.info("Reading blocked domains from {}", blockedName);
            try (Stream<String> lines = Files.lines(blockedFile.toPath(), StandardCharsets.UTF_8)) {
                lines.map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .forEach(line -> addDomainFromFile(line, domainValidator));
            }
        }
        return this;
    }

    /**
     * Валідує один рядок із вхідного файлу і додає домен до переліку.
     *
     * @param line рядок із файлу
     * @param domainValidator валідатор доменів
     */
    private void addDomainFromFile(String line, DomainValidator domainValidator) {
        if (line.length() > 255) {
            logger.warn("Skipping domain from file due to invalid length: {}", line);
            return;
        }
        try {
            String idnDomain = IDN.toASCII(line, IDN.ALLOW_UNASSIGNED);
            if (idnDomain.length() > 255) {
                logger.warn("Skipping domain after IDN conversion due to length: {}", idnDomain);
                return;
            }
            if (domainValidator.isValid(idnDomain)) {
                this.addBlockedDomainName(new BlockedDomain(idnDomain));
                logger.info("Added domain from file: {}", idnDomain);
            } else {
                logger.warn("Invalid domain in file: {}", line);
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to process domain from file: {} ({})", line, e.getMessage());
        }
    }

    /**
     * Додає домен до переліку, нормалізувавши ім'я.
     * <p>
     * Це <b>єдина</b> точка, через яку домени потрапляють у сховище — усіма
     * трьома шляхами: вхідні файли {@code blocked}, текстові розпорядження
     * і PDF. Тому нормалізація живе саме тут, а не у {@link #storeState()}:
     * так її бачить і {@link #getKnownDomainNames()}, тобто дорадчий перелік
     * не пропонуватиме {@code ivi.ru} лише через те, що в сховищі лежить
     * {@code www.ivi.ru}.
     * <p>
     * Дві дії, обидві — остання лінія оборони, спільна для всіх джерел:
     * <ul>
     * <li>зрізання службового префікса. {@link DomainValidatorUtil} робить
     * це для розпоряджень і PDF, але вхідні файли читаються повз нього, тож
     * у результаті роками жили {@code www.ivi.ru} поряд із зонами без
     * префікса. Дублікати, що виникають після зрізання, схлопує сам
     * {@code TreeSet};</li>
     * <li>відмова публічному суфіксу. Валідатор його не пропускає, але, знову
     * ж таки, лише на своїх шляхах — рядок {@code com.ua} у списку
     * провайдера інакше потрапив би в результат і поклав цілу зону.</li>
     * </ul>
     *
     * @param bdn об'єкт BlockedDomain
     * @return true, якщо домен додано успішно
     */
    public boolean addBlockedDomainName(BlockedDomain bdn) {
        String name = DomainValidatorUtil.stripServiceSubdomain(
                bdn.getDomainName(), this.serviceSubdomains, logger);
        if (DomainValidatorUtil.isPublicSuffix(name)) {
            logger.warn("Refusing to store a public suffix: {} (from {})", name, bdn.getDomainName());
            refusedCount++;
            return false;
        }
        if (!name.equals(bdn.getDomainName())) {
            logger.debug("Normalized {} to {}", bdn.getDomainName(), name);
            normalizedCount++;
            bdn = new BlockedDomain(name, bdn.getIsBlocked(), bdn.getDateTime());
        }
        return this.blockedDomains.add(bdn);
    }

    /**
     * Повертає всі відомі доменні імена — і ті, що підлягають блокуванню, і
     * ті, що були розблоковані.
     * <p>
     * Потрібно дорадчому переліку кандидатів
     * ({@link AbstractPDFParser#storePossiblyMissedDomains}): пропонувати до
     * ручної перевірки має сенс лише те, чого ми ще не знаємо. Розблоковані
     * домени теж входять — рішення щодо них уже прийняте, і повертати їх у
     * вигляді «а може, ви це пропустили?» не варто.
     *
     * @return імена всіх доменів, що фігурують у переліку
     */
    public TreeSet<String> getKnownDomainNames() {
        TreeSet<String> names = new TreeSet<>();
        for (BlockedDomain bd : this.blockedDomains) {
            names.add(bd.getDomainName());
        }
        return names;
    }

    /**
     * Зберігає перелік доменів у вихідний файл, указаний у blocked_result.
     * Включає лише домени зі статусом isBlocked = true.
     *
     * @return цей об'єкт для ланцюгових викликів
     * @throws IOException у разі помилок запису
     */
    public BlockedObjects storeState() throws IOException {
        TreeSet<String> blockedDomainsResultList = new TreeSet<>();
        for (BlockedDomain bd : this.blockedDomains) {
            if (bd.getIsBlocked()) {
                blockedDomainsResultList.add(bd.getDomainName());
            } else {
                blockedDomainsResultList.remove(bd.getDomainName());
            }
        }
        if (normalizedCount > 0 || refusedCount > 0) {
            logger.info("Normalized {} domain(s) by stripping service prefixes, refused {} public suffix(es); "
                    + "{} unique domain(s) to store",
                    normalizedCount, refusedCount, blockedDomainsResultList.size());
        }

        Path targetPath = Paths.get(this.blockedResultName.trim());
        Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(tempPath.toFile()),
                        "UTF-8"))) {
            logger.info("Writing blocked domains to {}", tempPath);
            for (String s : blockedDomainsResultList) {
                pw.println(s);
            }
            pw.flush();
        }
        try {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            logger.warn("Atomic move not supported, falling back to regular move: {}", e.getMessage());
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("Successfully stored blocked domains to {}", this.blockedResultName);
        return this;
    }
}

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
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
    private final TreeSet<BlockedDomain> blockedDomains;

    /**
     * Конструктор класа.
     *
     * @param p - об'єкт властивостей
     */
    public BlockedObjects(Properties p) {
        this.blockedNames = p.getProperty("blocked", "blocked.txt").split(";");
        this.blockedResultName = p.getProperty("blocked_result", "blocked.result.txt");
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
     * Додає домен до переліку.
     *
     * @param bdn об'єкт BlockedDomain
     * @return true, якщо домен додано успішно
     */
    public boolean addBlockedDomainName(BlockedDomain bdn) {
        return this.blockedDomains.add(bdn);
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

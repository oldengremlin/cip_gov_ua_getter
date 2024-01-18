/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Один з "роочих класів", який формує перелік об'єктів (доменів), що підлягають
 * блокуванню.
 *
 * @author olden
 */
public class jBlockedObjects {

    protected String currentPath; // Current dir
    protected String currentDir;  // Current dir using System
    private final String[] blockedNames;
    private final String blockedResultName;
    TreeSet<jBlockedDomain> blockedDomains;

    /**
     * Конструктор класа.
     *
     * @param p - об'єкт властивостей.
     */
    public jBlockedObjects(Properties p) {
        try {
            this.currentPath = new java.io.File(".").getCanonicalPath();
            this.currentDir = System.getProperty("user.dir");
        } catch (IOException ex) {
            Logger.getLogger(jBlockedObjects.class.getName()).log(Level.SEVERE, null, ex);
        }

        this.blockedNames = p.getProperty("blocked", "blocked.txt").split(";");
        this.blockedResultName = p.getProperty("blocked_result", "blocked.result.txt");
        this.blockedDomains = new TreeSet<>(
                new jBlockedDomainComparator()
        );
    }

    /**
     * Зчитуємо перелік доменів з файлів. Зберігає їх перелік в TreeSet. Так як
     * це перелік файлів, які були визначені для блокування до відповідного
     * розпорядження НКРЗІ по відстеженню, то дата для них буде початком епохи
     * (реалізовано у відповідному конструкторі класа jBlockedDomain).
     *
     * @return
     * @throws MalformedURLException
     * @throws IOException
     */
    public jBlockedObjects getBlockedDomainNames() throws MalformedURLException, IOException {

        for (String blockedName : blockedNames) {

            File blockedFile = new File(blockedName.trim());
            if (blockedFile.exists() && blockedFile.isFile() && blockedFile.canRead()) {
                System.err.println(LocalDateTime.now().toString().concat(" ").concat(
                        "Reading ".concat(blockedName)
                ));
                try (BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(blockedFile)
                        ))) {
                    String blockedDomainName;
                    while ((blockedDomainName = bufferedReader.readLine()) != null) {
                        this.addBlockedDomainName(new jBlockedDomain(blockedDomainName));
                    }
                }
            }

        }
        return this;
    }

    /**
     * Додаємо об'єкт блокуємого домена в перелік.
     *
     * @param bdn
     * @return
     */
    public boolean addBlockedDomainName(jBlockedDomain bdn) {
        return this.blockedDomains.add(bdn);
    }

    /**
     * Зберігаємо перелік доменів в вихідному файлі.
     *
     * @return
     * @throws UnsupportedEncodingException
     * @throws FileNotFoundException
     */
    public jBlockedObjects storeState() throws UnsupportedEncodingException, FileNotFoundException {

        TreeSet<String> blockedDomainsResultList = new TreeSet<>();
        for (jBlockedDomain bd : this.blockedDomains) {
            if (bd.getIsBlocked()) {
                blockedDomainsResultList.add(bd.getDomainName());
            } else {
                blockedDomainsResultList.remove(bd.getDomainName());
            }
        }

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(
                                this.blockedResultName.trim()
                        ),
                        "UTF-8"
                )
        )) {
            System.err.println(LocalDateTime.now().toString().concat(" ").concat(
                    "Writeing ".concat(this.blockedResultName)
            ));
            for (String s : blockedDomainsResultList) {
                pw.println(s);
            }
            pw.flush();
        }

        return this;

    }

}

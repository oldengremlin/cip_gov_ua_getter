/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Клас зчитує перелік доменів з відповідних text/plain файлів у розпорядженнях.
 *
 * @author olden
 */
public class jGetPrescript {

    protected final String urlPrescript;
    protected final URI uriCGUPrescript;
    protected final URL urlCGUPrescript;
    protected final URLConnection conCGUPrescript;
    protected String bodyPrescript;
    protected String id;
    protected String storePrescriptTo;

    /**
     * Конструктор класа. Зчитує перелік доменів для блокування з прикріпленого
     * text/plain файла.
     *
     * @param p - об'єкт властивостей.
     * @param i - ідентифікатор id прикріпленого text/plain файла з доменами для
     * блокування.
     * @throws MalformedURLException
     * @throws IOException
     */
    public jGetPrescript(Properties p, String i) throws MalformedURLException, IOException {
        this.id = i;
        this.urlPrescript = p.getProperty(
                "urlPrescript",
                "https://cip.gov.ua/services/cm/api/attachment/download?id="
        ).trim().concat(this.id);
        this.storePrescriptTo = p.getProperty(
                "store_prescript_to",
                "./Prescript"
        ).trim();
        if (!this.storePrescriptTo.matches("/$")) {
            this.storePrescriptTo = this.storePrescriptTo.concat("/");
        }
        this.uriCGUPrescript = URI.create(this.urlPrescript);
        this.urlCGUPrescript = uriCGUPrescript.toURL();
        this.conCGUPrescript = urlCGUPrescript.openConnection();

        this.conCGUPrescript.setRequestProperty("Accept-Encoding", "deflate");
        this.conCGUPrescript.setRequestProperty("Accept-Language", "uk");
        this.conCGUPrescript.setRequestProperty("Host", "cip.gov.ua");
        this.conCGUPrescript.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0");
        this.conCGUPrescript.setRequestProperty("Upgrade-Insecure-Requests", "1");

        try (Stream<String> lines = new BufferedReader(
                new InputStreamReader(this.conCGUPrescript.getInputStream()))
                .lines()) {
            this.bodyPrescript = lines.collect(Collectors.joining("\n"));
        }

    }

    /**
     * Із зчитаного переліка доменів в "хрін знає якому вигляді" (перепрошую за
     * емоції, але…) формуємо перелік доменів для блокування, за який не
     * соромно. Домени, що містять відмінні від латинки символи, перекодуються в
     * idn. На початку доменів прибираємо www та ftp (www зустрічається часто
     * (хм…), а ftp так, про всяк випадок, мало що…).
     *
     * @return
     */
    public String[] getBodyPrescript() {
        StringBuilder sb = new StringBuilder();
        for (String s : this.bodyPrescript.split("\n")) {
            String domain = IDN.toASCII(
                    s.replaceAll("\s+", "")
            ).toLowerCase()
                    .trim()
                    .replaceAll("^[^:]+://", "")
                    .replaceAll("^www\\.", "")
                    .replaceAll("^ftp\\.", "")
                    .replaceAll("/.*", "");

            URI uri = URI.create(domain);
            if (uri.getHost() != null) {
                domain = uri.getHost();
            }

            if (!domain.isBlank() && !domain.isEmpty() && domain.length() > 0) {
                sb.append(domain).append("\n");
            }
        }
        return sb.toString().split("\n");
    }

    /**
     * Зберігає копію розпорядження від НЦУ.
     *
     * @param fn - ім'я файла-розпорядження
     * @return
     */
    public jGetPrescript storePrescriptTo(String fn) {
        if (this.mkDir() && !this.isExists(fn)) {
            try {
                URL url = URI.create(this.urlPrescript).toURL();
                URLConnection oCon = url.openConnection();
                oCon.setRequestProperty("Accept-Encoding", "deflate");
                oCon.setRequestProperty("Accept-Language", "uk");
                oCon.setRequestProperty("Host", "cip.gov.ua");
                oCon.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0");
                oCon.setRequestProperty("Upgrade-Insecure-Requests", "1");

                try (ReadableByteChannel rbc = Channels.newChannel(oCon.getInputStream()); FileOutputStream fos = new FileOutputStream(storePrescriptTo + this.id + "~" + fn)) {
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                } catch (IOException ex) {
                    Logger.getLogger(jGetPrescript.class.getName()).log(Level.SEVERE, null, ex);
                }

//                // https://www.digitalocean.com/community/tutorials/java-download-file-url
//                try (ReadableByteChannel rbc = Channels.newChannel(url.openStream()); FileOutputStream fos = new FileOutputStream(storePrescriptTo + this.id + "~" + fn)) {
//                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
//                } catch (IOException ex) {
//                    Logger.getLogger(jGetPrescript.class.getName()).log(Level.SEVERE, null, ex);
//                }
            } catch (MalformedURLException ex) {
                Logger.getLogger(jGetPrescript.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Logger.getLogger(jGetPrescript.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return this;
    }

    protected boolean mkDir() {
        File md = new File(this.storePrescriptTo);
        if (md.exists()) {
            return md.isDirectory();
        }
        return md.mkdirs();
    }

    protected boolean isExists(String fn) {
        File f = new File(this.storePrescriptTo + this.id + "~" + fn);
        return f.exists() && f.canRead() && f.canWrite();
    }

}

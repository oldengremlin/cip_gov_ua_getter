/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Клас реалізує зчитування розпоряджень щодо блокування доменів через API.
 *
 * @author olden
 */
public class jCGUGetter {

    protected final String urlArticles;
    protected final URI uriCGU;
    protected final URL urlCGU;
    protected final URLConnection conCGU;
    protected String jsonBodyArticles;

    /**
     * Конструктор класа. Підключаємося до API і зчитуємо отримані дані,
     * формуючи з них правильний JSON, для подальшого аналізу.
     *
     * @param p - об'єкт властивостей.
     * @throws MalformedURLException
     * @throws IOException
     */
    public jCGUGetter(Properties p) throws MalformedURLException, IOException {
        this.urlArticles = p.getProperty(
                "urlArticles",
                "https://cip.gov.ua/services/cm/api/articles?page=0&size=1000&tagId=60751"
        ).trim();
        this.uriCGU = URI.create(this.urlArticles);
        this.urlCGU = uriCGU.toURL();
        this.conCGU = urlCGU.openConnection();
        this.conCGU.setDoOutput(true);
        this.conCGU.setRequestProperty("Accept-Encoding", "deflate");
        this.conCGU.setRequestProperty("Accept-Language", "uk");

//        this.conCGU.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
//        this.conCGU.setRequestProperty("Accept-Encoding", "gzip, deflate, br, zstd");
//        this.conCGU.setRequestProperty("Accept-Language", "uk,en-US;q=0.9,en;q=0.8,ru;q=0.7");
//        this.conCGU.setRequestProperty("Cache-Control", "max-age=0");
//        this.conCGU.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
//        this.conCGU.setRequestProperty("cookie:", "bm_sv=58FA686B2F22E692D23DD2917815941C~YAAQkHkmF84nQLGSAQAAYjxD5xkseYV0DowmuLTivs9J5aUCRgJ8r0Syan58I9qSvQH/waeN/f/EHfYXxeUp5OnSSHgCuKrgnR2eNWv4ZoxIWV8qCYtHo7WdFGXwfJiSHSZsgIYZrISu7cONqj4plnIv0ojPtS1PAG4oETxXjPGlQw9wY9o0/GzanulzNGOTq35YdNJ3IXFLXvQQCESQcvo+ja6Mn42rVVkW6/0+9U0m1UstdI8ZHq3SbybqF5P8~1");
        try (Stream<String> lines = new BufferedReader(
                new InputStreamReader(this.conCGU.getInputStream()))
                .lines()) {
            /*
            this.jsonBodyArticles = lines.collect(Collectors.joining("\n"))
                    .replaceAll("^[^\\[]*\\[", "")
                    .replaceAll("\\][^\\]]*$", "")
                    .trim();
             */
            this.jsonBodyArticles = "{ posts: ".concat(lines.collect(Collectors.joining("\n"))).concat("}");
        }
    }

    /**
     * Повертає зчитаний JSON, для подальшого аналізу.
     *
     * @return
     */
    public String getJsonBody() {
        return this.jsonBodyArticles;
    }
}

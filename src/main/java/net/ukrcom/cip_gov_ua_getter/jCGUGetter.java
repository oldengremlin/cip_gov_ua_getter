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
 *
 * @author olden
 */
public class jCGUGetter {

    protected final String urlArticles;
    protected final URI uriCGU;
    protected final URL urlCGU;
    protected final URLConnection conCGU;
    protected String jsonBodyArticles;

    public jCGUGetter(Properties p) throws MalformedURLException, IOException {
        this.urlArticles = p.getProperty(
                "urlArticles",
                "https://cip.gov.ua/services/cm/api/articles"
        ).trim();
        this.uriCGU = URI.create(this.urlArticles);
        this.urlCGU = uriCGU.toURL();
        this.conCGU = urlCGU.openConnection();
        this.conCGU.setDoOutput(true);
        this.conCGU.setRequestProperty("Accept-Encoding", "deflate");
        this.conCGU.setRequestProperty("Accept-Language", "uk");

        //this.conCGU.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        //this.conCGU.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
        //this.conCGU.setRequestProperty("Accept-Language", "uk,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        //this.conCGU.setRequestProperty("Cache-Control", "max-age=0");
        //this.conCGU.setRequestProperty("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"");
        //this.conCGU.setRequestProperty("Sec-Ch-Ua-Mobile", "?0");
        //this.conCGU.setRequestProperty("Sec-Ch-Ua-Platform", "\"Linux\"");
        //this.conCGU.setRequestProperty("Sec-Fetch-Dest", "document");
        //this.conCGU.setRequestProperty("Sec-Fetch-Mode", "navigate");
        //this.conCGU.setRequestProperty("Sec-Fetch-Site", "none");
        //this.conCGU.setRequestProperty("Upgrade-Insecure-Requests", "1");
        //this.conCGU.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
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

    public String getJsonBody() {
        return this.jsonBodyArticles;
    }
}

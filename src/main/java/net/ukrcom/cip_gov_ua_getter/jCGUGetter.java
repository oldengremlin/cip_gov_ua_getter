/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
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

        /**
         * * *
         */
//        HttpURLConnection con = (HttpURLConnection) urlCGU.openConnection();
//        con.setDoOutput(true);
//        con.setRequestMethod("GET");
//        con.setRequestProperty("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
//        con.setRequestProperty("accept-encoding", "gzip, deflate, br, zstd");
//        con.setRequestProperty("accept-language", "uk,en-US;q=0.9,en;q=0.8,ru;q=0.7");
//        con.setRequestProperty("cache-control", "max-age=0");
//        con.setRequestProperty("priority", "u=0, i");
//        con.setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"");
//        con.setRequestProperty("sec-ch-ua-mobile", "?0");
//        con.setRequestProperty("sec-ch-ua-platform", "\"Linux\"");
//        con.setRequestProperty("sec-fetch-dest", "document");
//        con.setRequestProperty("sec-fetch-mode", "navigate");
//        con.setRequestProperty("sec-fetch-site", "none");
//        con.setRequestProperty("sec-fetch-user", "?1");
//        con.setRequestProperty("upgrade-insecure-requests", "1");
//        con.setRequestProperty("user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
//        con.connect();
//        System.out.println(con.getHeaderFields());
//        int responseCode = con.getResponseCode();
//        System.out.println("GET Response Code :: " + responseCode);
//        if (responseCode == HttpURLConnection.HTTP_OK) {
//            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
//            String inputLine;
//            StringBuffer response = new StringBuffer();
//
//            while ((inputLine = in.readLine()) != null) {
//                response.append(inputLine);
//            }
//            in.close();
//
//            // print result
//            System.out.println(response.toString());
//        } else {
//            System.out.println("GET request did not work.");
//        }
//        con.disconnect();
        /**
         * * *
         */
//        this.conCGU.setDoOutput(true);
        this.conCGU.setRequestProperty("Accept-Encoding", "deflate");
        this.conCGU.setRequestProperty("Accept-Language", "uk");
        this.conCGU.setRequestProperty("Host", "cip.gov.ua");
        this.conCGU.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0");
        this.conCGU.setRequestProperty("Upgrade-Insecure-Requests", "1");
        System.err.println(this.conCGU.getHeaderFields());
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
            System.err.println(this.jsonBodyArticles);
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

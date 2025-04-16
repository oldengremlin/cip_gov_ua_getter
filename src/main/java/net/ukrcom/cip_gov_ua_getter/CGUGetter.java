/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import com.microsoft.playwright.*;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клас реалізує зчитування розпоряджень щодо блокування доменів через API.
 *
 * @author olden
 */
public class CGUGetter {

    private static final Logger logger = LoggerFactory.getLogger(CGUGetter.class);

    protected final String urlArticles;
    protected String jsonBodyArticles;
    private final String userAgent;
    private final String secChUa;
    private final Properties prop;
    private final boolean debug;

    /**
     * Конструктор класа. Підключаємося до API і зчитуємо отримані дані,
     * формуючи з них правильний JSON, для подальшого аналізу.
     *
     * @param p - об'єкт властивостей.
     */
    public CGUGetter(Properties p) {

        this.prop = p;
        this.debug = this.prop.getProperty("debug", "false").equalsIgnoreCase("true");

        this.urlArticles = p.getProperty(
                "urlArticles",
                "https://cip.gov.ua/services/cm/api/articles?page=0&size=1000&tagId=60751"
        ).trim();
        this.userAgent = p.getProperty(
                "userAgent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        ).trim();
        this.secChUa = p.getProperty(
                "secChUa",
                "\"Chromium\";v=\"129\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"129\""
        ).trim();

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setChannel("chrome"))) {
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(this.userAgent)
                    .setLocale("uk-UA")
                    .setExtraHTTPHeaders(Map.of(
                            "Accept", "application/json, text/plain, */*",
                            "Accept-Language", "uk,en-US;q=0.9,en;q=0.8,ru;q=0.7",
                            "Sec-Ch-Ua", this.secChUa,
                            "Sec-Fetch-Dest", "empty",
                            "Sec-Fetch-Mode", "cors",
                            "Sec-Fetch-Site", "same-origin"
                    )));

            Page page = context.newPage();

            // Блокуємо запити до Google Analytics і Google Tag Manager
            page.route("**/*google-analytics.com/**", route -> {
                String url = route.request().url();
                logger.debug("Blocked Google Analytics request: {}", url);
                route.abort();
            });
            page.route("**/*googletagmanager.com/**", route -> {
                String url = route.request().url();
                logger.debug("Blocked Google Tag Manager request: {}", url);
                route.abort();
            });

            // Блокуємо статичні ресурси (зображення, шрифти, стилі)
            page.route("**/*.{jpg,jpeg,png,svg,woff,woff2,ttf,css,gif,ico}", route -> {
                String url = route.request().url();
                logger.debug("Blocked static resource: {}", url);
                route.abort();
            });

            // Логування запитів і відповідей у дебаг-режимі
            if (this.debug) {
                page.onRequest(request -> logger.debug("Playwright request: {} {}", request.method(), request.url()));
                page.onResponse(response -> logger.debug("Playwright response: {} {} {}",
                        response.status(), response.request().method(), response.url()));
            }

            Response response = page.waitForResponse(
                    r -> r.url().contains("articles"),
                    () -> page.navigate(this.urlArticles)
            );

            String rawResponse = response.text();
            this.jsonBodyArticles = "{ \"posts\": " + rawResponse + " }";
            logger.info("Successfully fetched articles for URL: {}", this.urlArticles);
        } catch (Exception e) {
            logger.error("Failed to fetch articles: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch articles: " + e.getMessage(), e);
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

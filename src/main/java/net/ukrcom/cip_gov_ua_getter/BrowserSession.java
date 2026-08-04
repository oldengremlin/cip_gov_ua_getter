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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Спільна сесія headless-браузера на весь запуск утиліти.
 * <p>
 * Раніше {@code CGUGetter} і {@code GetPrescript} піднімали власний
 * {@link Playwright} і власний {@link Browser} на кожне звернення до сервера —
 * тобто на кожне вкладення і на кожну повторну спробу. Запуск Chromium коштує
 * секунди, тож на «холодному» запуску саме це, а не мережа, з'їдало основну
 * частину часу.
 * <p>
 * Тут браузер запускається один раз, а споживачі отримують лише нову
 * {@link Page}. Контексти створюються лениво й окремо для JSON- і
 * text-запитів, бо вони відрізняються заголовком {@code Accept}.
 * <p>
 * Це <b>не</b> вмикає паралелізм: звернення до {@code cip.gov.ua} лишаються
 * послідовними, з паузами між ними. Клас синхронізований лише щоб захистити
 * лениву ініціалізацію.
 *
 * @author olden
 */
public class BrowserSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BrowserSession.class);

    private final String userAgent;
    private final String secChUa;
    private final boolean debug;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext jsonContext;
    private BrowserContext textContext;

    /**
     * Конструктор класа. Одразу піднімає браузер.
     *
     * @param p - об'єкт властивостей
     */
    public BrowserSession(Properties p) {
        this.debug = p.getProperty("debug", "false").equalsIgnoreCase("true");
        this.userAgent = p.getProperty(
                "userAgent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        ).trim();
        this.secChUa = p.getProperty(
                "secChUa",
                "\"Chromium\";v=\"129\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"129\""
        ).trim();
        launch();
    }

    private void launch() {
        this.playwright = Playwright.create();
        this.browser = this.playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox"))
                .setHeadless(true)
                .setChannel("chrome"));
        logger.debug("Browser session started");
    }

    /**
     * Нова сторінка в контексті для JSON-запитів (список розпоряджень).
     *
     * @return сторінка; закривати обов'язково
     */
    public synchronized Page newJsonPage() {
        if (jsonContext == null) {
            jsonContext = newContext("application/json, text/plain, */*");
        }
        return jsonContext.newPage();
    }

    /**
     * Нова сторінка в контексті для text/binary-запитів (вкладення).
     *
     * @return сторінка; закривати обов'язково
     */
    public synchronized Page newTextPage() {
        if (textContext == null) {
            textContext = newContext("text/plain, */*");
        }
        return textContext.newPage();
    }

    /**
     * Створює контекст із потрібним заголовком Accept, блокуванням аналітики
     * та статичних ресурсів, і — у дебаг-режимі — логуванням запитів.
     *
     * @param accept значення заголовка Accept
     * @return готовий контекст
     */
    private BrowserContext newContext(String accept) {
        if (browser == null) {
            throw new IllegalStateException("Browser session is not available (closed or failed to restart)");
        }
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(this.userAgent)
                .setLocale("uk-UA")
                .setExtraHTTPHeaders(Map.of(
                        "Accept", accept,
                        "Accept-Language", "uk,en-US;q=0.9,en;q=0.8,ru;q=0.7",
                        "Sec-Ch-Ua", this.secChUa,
                        "Sec-Fetch-Dest", "empty",
                        "Sec-Fetch-Mode", "cors",
                        "Sec-Fetch-Site", "same-origin"
                )));

        // Блокуємо запити до Google Analytics і Google Tag Manager
        context.route("**/*google-analytics.com/**", route -> {
            logger.debug("Blocked Google Analytics request: {}", route.request().url());
            route.abort();
        });
        context.route("**/*googletagmanager.com/**", route -> {
            logger.debug("Blocked Google Tag Manager request: {}", route.request().url());
            route.abort();
        });

        // Блокуємо статичні ресурси (зображення, шрифти, стилі)
        context.route("**/*.{jpg,jpeg,png,svg,woff,woff2,ttf,css,gif,ico}", route -> {
            logger.debug("Blocked static resource: {}", route.request().url());
            route.abort();
        });

        // Логування запитів і відповідей у дебаг-режимі
        if (this.debug) {
            context.onRequest(request -> logger.debug("Playwright request: {} {}",
                    request.method(), request.url()));
            context.onResponse(response -> logger.debug("Playwright response: {} {} {}",
                    response.status(), response.request().method(), response.url()));
        }

        return context;
    }

    /**
     * Перезапускає браузер. Викликається між повторними спробами після
     * мережевого збою (наприклад, ERR_NETWORK_CHANGED), який міг лишити
     * контекст у непридатному стані.
     */
    public synchronized void reset() {
        logger.warn("Resetting browser session");
        shutdown();
        launch();
    }

    private void shutdown() {
        jsonContext = null;
        textContext = null;
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (RuntimeException e) {
            logger.debug("Error closing browser: {}", e.getMessage());
        } finally {
            browser = null;
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException e) {
            logger.debug("Error closing playwright: {}", e.getMessage());
        } finally {
            playwright = null;
        }
    }

    @Override
    public synchronized void close() {
        shutdown();
        logger.debug("Browser session closed");
    }
}

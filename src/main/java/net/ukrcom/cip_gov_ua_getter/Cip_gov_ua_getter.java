/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Консольна утиліта для збору та обробки розпоряджень про блокування доменів.
 *
 * @author olden
 */
public class Cip_gov_ua_getter {

    private static final Logger logger = LoggerFactory.getLogger(Cip_gov_ua_getter.class);

    /**
     * Основний процес.
     *
     * @param args аргументи командного рядка (не використовуються)
     */
    public static void main(String[] args) {
        try {
            // Завантаження конфігурації
            Properties prop = new Properties();
            String configPath = args.length > 0 ? args[0] : "cip.gov.ua.properties";
            try (InputStream input = new FileInputStream(configPath)) {
                prop.load(input);
            } catch (IOException e) {
                logger.error("Failed to load config from {}: {}", configPath, e.getMessage(), e);
                throw new RuntimeException("Failed to load config", e);
            }
            BlockedObjects bo = new BlockedObjects(prop).getBlockedDomainNames();

            CGUGetter cguGetter = new CGUGetter(prop);
            ParseCGUArticlesJson parseCGUArticlesJson = new ParseCGUArticlesJson(cguGetter.getJsonBody());

            JSONArray posts = parseCGUArticlesJson.getPosts();
            for (int i = 0; i < posts.length(); i++) {
                JSONObject post = posts.getJSONObject(i);
                String title = post.getString("title");

                // Ігноруємо непубліковані пости
                if (!post.getString("status").equalsIgnoreCase("PUBLISHED")) {
                    logger.warn("Skipping unpublished post: {} - {}", post.getString("date"), title);
                    continue;
                }

                // Перевіряємо, чи пост стосується блокування/обмеження
                if (!(title.matches(".*блокування.*") || title.matches(".*обмеження доступу.*"))) {
                    logger.warn("Skipping unrelated post: {} - {}", post.getString("date"), title);
                    continue;
                }

                // Визначаємо дію (блокувати чи розблокувати)
                boolean block = !title.matches(".*розблокування.*") && !title.matches(".*припинення тимчасового.*");

                // Обробляємо вкладення
                JSONArray postAttachments = post.getJSONArray("attachments");
                for (int j = 0; j < postAttachments.length(); j++) {
                    JSONObject attachment = postAttachments.getJSONObject(j);
                    String id = String.valueOf(attachment.getInt("id"));
                    String mimeType = attachment.getString("mimeType");
                    String fileName = attachment.getString("originalFileName");

                    GetPrescript gp = new GetPrescript(prop, id).storePrescriptTo(fileName);

                    if (!mimeType.equalsIgnoreCase("text/plain")) {
                        logger.info("{} {} {} {} \"{}\"",
                                LocalDateTime.now(), post.getString("date"), block ? "+" : "-", id, fileName);
                        continue;
                    }

                    for (String domain : gp.getBodyPrescript()) {
                        BlockedDomain bd = new BlockedDomain(domain, block, post.getString("date"));
                        if (bo.addBlockedDomainName(bd)) {
                            logger.info("{} {} [ {} \"{}\"]",
                                    LocalDateTime.now(), bd, id, fileName);
                        }
                    }

                    try {
                        Thread.sleep(1000 + (long) (Math.random() * 1000)); // 1-2 секунди
                    } catch (InterruptedException e) {
                        logger.error("Interrupted during delay: {}", e.getMessage(), e);
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // Зберігаємо результати
            bo.storeState();
            logger.info("Successfully stored blocked domains state");

        } catch (IOException e) {
            logger.error("Failed to process articles: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process articles", e);
        } catch (JSONException e) {
            logger.error("Failed to parse JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }
}

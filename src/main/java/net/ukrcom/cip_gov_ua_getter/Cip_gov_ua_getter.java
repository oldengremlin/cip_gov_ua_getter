/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author olden
 */
public class Cip_gov_ua_getter {

    /**
     * Основний процес. Це не якийсь там GUI, а проста консольна утиліта, тому
     * все просто.
     *
     * @param args
     */
    public static void main(String[] args) {
        try {

            /*
            В cip.gov.ua.properties має описуватися всього декілька ключових опцій:
            urlArticles - це, так би мовити, основний шлях до новин по блокуванню,
            що реалізує API який видає нам перелік "новин" в форматі JSON;
            urlPrescript - а тут міститься частина url для завантаження text/plain
            переліку доменів для блокування;
            blocked - перелік локальних файлів, в яких міститься перелік блокуємих
            доменів. Файлій може бути декілька, в такому випадку вони перераховуються
            через ";";
            blocked_result - а сюди записуємо сформований перелік доменів для
            блокування. При цьому назва може збігатися (а може не збігатися) з
            назвою того чи іншого файлу з опції blocked.
             */
            Properties prop = new Properties();
            try (InputStream input = new FileInputStream("cip.gov.ua.properties")) {
                prop.load(input);
            }
            jBlockedObjects bo = new jBlockedObjects(prop).getBlockedDomainNames();

            jCGUGetter cguGetter = new jCGUGetter(prop);

            jParseCGUArticlesJson parseCGUArticlesJson = new jParseCGUArticlesJson(cguGetter.getJsonBody());
            //HashMap<String, Object> hashMap = new HashMap<>(Utility.jsonToMap(jCGUGetter.getJsonBody()));

            JSONArray posts = parseCGUArticlesJson.getPosts();
            for (int i = 0; i < posts.length(); i++) {
                JSONObject post = (JSONObject) posts.get(i);

                /* 
                Ігноруємо всі новини, що не містять статусу PUBLISHED. В принципі
                таких не було виявлено, але може ж бути що завгодно. Тому й введено
                цю перевірку.
                 */
                if (!post.getString("status").equalsIgnoreCase("PUBLISHED")) {
                    System.err.println(
                            LocalDateTime.now().toString().concat(" ").concat(
                                    post.getString("date")
                                            .concat(" # ")
                                            .concat(post.getString("title"))
                            )
                    );
                    continue;
                }

                /*
                "Геніальність" розробників переліку розпоряджень полягає в тому, що
                через API дізнатися статус розпорядження - блокування чи розблокування
                неможливо, тому аналізуємо title повідомлення і визначаємося з
                дією, яку від нас вимагає розпорядження.
                 */
                if (!post.getString("title").matches(".*блокування.*")) {
                    System.err.println(
                            LocalDateTime.now().toString().concat(" ").concat(
                                    post.getString("date")
                                            .concat(" : ")
                                            .concat(post.getString("title"))
                            )
                    );
                    continue;
                }

                boolean block = true;
                if (post.getString("title").matches(".*розблокування.*")) {
                    block = false;
                }

                /*
                Аналізуємо перелік прикріплених до розпорядження файлів.
                Якщо це не text/plain то в err виводимо назву файла і навіть не
                намагаємося його обробити.
                Якщо це text/plain то в out виводимо назву файла, зчитуємо та
                обробляємо перелік доменів в ньому.
                 */
                JSONArray postAttachments = post.getJSONArray("attachments");
                for (int j = 0; j < postAttachments.length(); j++) {

                    JSONObject attachment = (JSONObject) postAttachments.get(j);
                    String id = Integer.toString(attachment.getInt("id"));
                    new jGetPrescript(prop, id).storePrescriptTo(attachment.getString("originalFileName"));

                    if (!attachment.getString("mimeType").equalsIgnoreCase("text/plain")) {
                        System.err.println(
                                LocalDateTime.now().toString().concat(" ").concat(
                                        post.getString("date")
                                                .concat(block ? " + " : " - ")
                                                .concat(id)
                                                .concat(" \"")
                                                .concat(attachment.getString("originalFileName"))
                                                .concat("\"")
                                )
                        );
                        continue;
                    }

                    for (String domain : new jGetPrescript(prop, id).getBodyPrescript()) {
                        jBlockedDomain bd = new jBlockedDomain(domain, block, post.getString("date"));
                        if (bo.addBlockedDomainName(bd)) {
                            System.out.println(
                                    LocalDateTime.now().toString().concat(" ").concat(
                                            bd.toString()
                                                    .concat(" [ ")
                                                    .concat(id)
                                                    .concat(" \"")
                                                    .concat(attachment.getString("originalFileName"))
                                                    .concat("\" ]")
                                    )
                            );
                        }

                    }
                }
            }
            /*
            Ну й наша мета - формуємо вихідний файл.
             */
            bo.storeState();
        } catch (IOException | JSONException ex) {
            Logger.getLogger(Cip_gov_ua_getter.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}

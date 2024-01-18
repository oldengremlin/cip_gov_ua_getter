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

    public static void main(String[] args) {
        try {

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

                JSONArray postAttachments = post.getJSONArray("attachments");
                for (int j = 0; j < postAttachments.length(); j++) {

                    JSONObject attachment = (JSONObject) postAttachments.get(j);
                    String id = Integer.toString(attachment.getInt("id"));
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
            bo.storeState();
        } catch (IOException | JSONException ex) {
            Logger.getLogger(Cip_gov_ua_getter.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Клас, що розбирає JSON з переліком розпоряджень.
 *
 * @author olden
 */
public class ParseCGUArticlesJson {

    protected final JSONObject json;
    protected final JSONArray posts;

    /**
     * Конструктор класа. Розбираємо сформований JSON з розпорядженнями.
     *
     * @param json_data
     * @throws JSONException
     */
    public ParseCGUArticlesJson(String json_data) throws JSONException {
        this.json = new JSONObject(json_data);
        this.posts = json.getJSONArray("posts");
    }

    /**
     * Повертаємо масив з розібраними й відокремленими один від одного
     * розпорядженнями, для подальшого аналізу.
     *
     * @return @throws JSONException
     */
    public JSONArray getPosts() throws JSONException {
        return this.posts;
    }
}

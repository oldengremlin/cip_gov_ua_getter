/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author olden
 */
public class jParseCGUArticlesJson {

    protected final JSONObject json;
    protected final JSONArray posts;

    public jParseCGUArticlesJson(String json_data) throws JSONException {
        this.json = new JSONObject(json_data);
        this.posts = json.getJSONArray("posts");
    }

    public JSONArray getPosts() throws JSONException {
        return this.posts;
    }
}

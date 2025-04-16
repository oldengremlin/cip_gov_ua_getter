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

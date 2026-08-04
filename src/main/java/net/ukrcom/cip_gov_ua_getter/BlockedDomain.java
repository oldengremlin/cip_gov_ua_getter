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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class BlockedDomain {

    private static final LocalDateTime EPOCH_START = Instant
            .ofEpochMilli(0)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();

    protected final String domainName;
    protected final boolean isBlocked;
    protected final LocalDateTime dateTime;

    /**
     * Конструктор класа.
     *
     * @param dn - ім'я домена
     * @param b - статус операції над доменом
     * @param dt - дата в форматі LocalDateTime
     */
    public BlockedDomain(String dn, boolean b, LocalDateTime dt) {
        this.domainName = validateDomainName(dn);
        this.isBlocked = b;
        this.dateTime = dt;
    }

    /**
     * Конструктор класа.
     *
     * @param dn - ім'я домена
     * @param b - статус операції над доменом
     * @param s - дата в текстовому форматі. Береться з атрибута date.
     */
    public BlockedDomain(String dn, boolean b, String s) {
        this.domainName = validateDomainName(dn);
        this.isBlocked = b;
        this.dateTime = parseDateTime(s);
    }

    /**
     * Конструктор класа. Статус - блокування. Дата - початок епохи.
     *
     * @param dn - ім'я домена
     */
    public BlockedDomain(String dn) {
        this.domainName = validateDomainName(dn);
        this.isBlocked = true;
        this.dateTime = EPOCH_START;
    }

    /**
     * Перевіряє коректність імені домена.
     *
     * @param dn - ім'я домена
     * @return те саме ім'я, якщо воно валідне
     */
    private static String validateDomainName(String dn) {
        if (dn == null || dn.isBlank() || dn.length() > 255) {
            throw new IllegalArgumentException("Domain name is invalid or exceeds 255 characters: " + dn);
        }
        return dn;
    }

    /**
     * Розбирає дату з тексту. Спершу як локальну дату-час, потім як дату зі
     * зсувом (наприклад, із суфіксом "Z"). Якщо не вдалося — початок епохи.
     *
     * @param s - дата в текстовому форматі
     * @return розібрана дата або початок епохи
     */
    private static LocalDateTime parseDateTime(String s) {
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toLocalDateTime();
            } catch (DateTimeParseException e2) {
                return EPOCH_START;
            }
        }
    }

    /**
     * Повертає ім'я домена.
     *
     * @return
     */
    public String getDomainName() {
        return domainName;
    }

    /**
     * Повертає статус.
     *
     * @return
     */
    public boolean getIsBlocked() {
        return this.isBlocked;
    }

    /**
     * Повертає дату та час.
     *
     * @return
     */
    public LocalDateTime getDateTime() {
        return this.dateTime;
    }

    /**
     * Повертає стан екземпляра класа в текстовому вигляді.
     *
     * @return
     */
    @Override
    public String toString() {
        return "[".concat(getDateTime().toString())
                .concat(getIsBlocked() ? " + " : " - ")
                .concat(getDomainName())
                .concat("]");
    }

}

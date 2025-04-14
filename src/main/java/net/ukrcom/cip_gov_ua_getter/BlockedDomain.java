package net.ukrcom.cip_gov_ua_getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
/**
 * Конструктор класа.
 *
 * @param dn - ім'я домена
 * @param b - статус операції над доменом (true - блокувати, false -
 * розблокувати)
 * @param s - дата в текстовому форматі ISO 8601 (наприклад,
 * "2023-10-15T12:00:00"), береться з атрибута date
 */
public class BlockedDomain {

    protected String domainName;
    protected boolean isBlocked;
    protected LocalDateTime dateTime;

    /**
     * Конструктор класа.
     *
     * @param dn - ім'я домена
     * @param b - статус операції над доменом
     * @param dt - дата в форматі LocalDateTime
     */
    public BlockedDomain(String dn, boolean b, LocalDateTime dt) {
        this.domainName = dn;
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
        this.domainName = dn;
        this.isBlocked = b;
        this.dateTime = LocalDateTime.parse(
                s.replaceAll("Z$", "")
        );
    }

    /**
     * Конструктор класа. Статус - блокування. Дата - початок епохи.
     *
     * @param dn - ім'я домена
     */
    public BlockedDomain(String dn) {
        this.domainName = dn;
        this.isBlocked = true;
        this.dateTime = Instant
                .ofEpochMilli(0)
                .atZone(
                        ZoneId.systemDefault()
                )
                .toLocalDateTime();
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
     * Встановлює ім'я домена.
     *
     * @param s
     */
    public void setDomainName(String s) {
        this.domainName = s;
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
     * Встановлює статус.
     *
     * @param b
     */
    public void setIsBlocked(boolean b) {
        this.isBlocked = b;
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
     * Встановлює дату та час з LocalDateTime.
     *
     * @param d
     */
    public void setDateTime(LocalDateTime d) {
        this.dateTime = d;
    }

    /**
     * Встановлює дату та час, аналізуючи строку.
     *
     * @param s
     */
    public void setDateTime(String s) {
        this.dateTime = LocalDateTime.parse(s);
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

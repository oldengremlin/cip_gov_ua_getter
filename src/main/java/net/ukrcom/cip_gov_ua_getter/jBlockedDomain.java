package net.ukrcom.cip_gov_ua_getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
/**
 *
 * @author olden
 */
public class jBlockedDomain {

    protected String domainName;
    protected boolean isBlocked;
    protected LocalDateTime dateTime;

    public jBlockedDomain(String dn, boolean b, LocalDateTime dt) {
        this.domainName = dn;
        this.isBlocked = b;
        this.dateTime = dt;
    }

    public jBlockedDomain(String dn, boolean b, String s) {
        this.domainName = dn;
        this.isBlocked = b;
        this.dateTime = LocalDateTime.parse(
                s.replaceAll("Z$", "")
        );
    }

    public jBlockedDomain(String dn) {
        this.domainName = dn;
        this.isBlocked = true;
        this.dateTime = Instant
                .ofEpochMilli(0)
                .atZone(
                        ZoneId.systemDefault()
                )
                .toLocalDateTime();
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String s) {
        this.domainName = s;
    }

    public boolean getIsBlocked() {
        return this.isBlocked;
    }

    public void setIsBlocked(boolean b) {
        this.isBlocked = b;
    }

    public LocalDateTime getDateTime() {
        return this.dateTime;
    }

    public void setDateTime(LocalDateTime d) {
        this.dateTime = d;
    }

    public void setDateTime(String s) {
        this.dateTime = LocalDateTime.parse(s);
    }

    @Override
    public String toString() {
        return "[".concat(getDateTime().toString())
                .concat(getIsBlocked() ? " + " : " - ")
                .concat(getDomainName())
                .concat("]");
    }

}

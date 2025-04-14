/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import java.time.ZoneOffset;
import java.util.Comparator;

/**
 * Компаратор для формування TreeSet з об'єктів jBlockedDomain. Порядок
 * сортування задається по імені домена, як основному крітерію, далі по даті та
 * часу видання розпорядження. Якщо ж дата та час збігаються то першим стає
 * розпорядження, що блокує домен.
 *
 * @author olden
 */
public class BlockedDomainComparator implements Comparator {

    @Override
    public int compare(Object o1, Object o2) {

        int retVal = 0;

        BlockedDomain d1 = (BlockedDomain) o1;
        BlockedDomain d2 = (BlockedDomain) o2;
        int compareTo = d1.getDomainName().compareTo(d2.getDomainName());
        compareTo = (compareTo > 0 ? 1 : (compareTo < 0 ? -1 : 0));

        if (compareTo != 0) {
            retVal = compareTo;
        } else {
            if (d1.getDateTime().toEpochSecond(ZoneOffset.UTC) > d2.getDateTime().toEpochSecond(ZoneOffset.UTC)) {
                retVal = 1;
            } else if (d1.getDateTime().toEpochSecond(ZoneOffset.UTC) < d2.getDateTime().toEpochSecond(ZoneOffset.UTC)) {
                retVal = -1;
            } else {
                if (d1.getIsBlocked() && !d2.getIsBlocked()) {
                    retVal = 1;
                } else if (!d1.getIsBlocked() && d2.getIsBlocked()) {
                    retVal = -1;
                } else {
                    retVal = 0;
                }
            }
        }

        return retVal;
    }
}

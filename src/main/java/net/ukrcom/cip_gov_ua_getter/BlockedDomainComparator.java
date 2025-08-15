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
        BlockedDomain d1 = (BlockedDomain) o1;
        BlockedDomain d2 = (BlockedDomain) o2;
        int compare = d1.getDomainName().compareTo(d2.getDomainName());
        if (compare != 0) {
            return compare;
        }
        compare = d1.getDateTime().compareTo(d2.getDateTime());
        if (compare != 0) {
            return compare;
        }
        return Boolean.compare(d1.getIsBlocked(), d2.getIsBlocked());
    }
    /*
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
     */

}

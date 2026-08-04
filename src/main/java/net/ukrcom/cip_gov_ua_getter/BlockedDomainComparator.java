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
 * Компаратор для формування TreeSet з об'єктів BlockedDomain.
 * <p>
 * Порядок сортування: спершу за іменем домена, далі за датою та часом видання
 * розпорядження. Якщо дата й час збігаються — першим іде розпорядження про
 * <b>розблокування</b>, а блокування стає останнім.
 * <p>
 * Такий порядок навмисний: {@link BlockedObjects#storeState()} проходить по
 * множині послідовно за принципом «останній перемагає», тож при однаковій даті
 * виграє блокування. Це безпечна поведінка за замовчуванням — у разі
 * суперечливих розпоряджень домен лишається заблокованим.
 *
 * @author olden
 */
public class BlockedDomainComparator implements Comparator<BlockedDomain> {

    private static final Comparator<BlockedDomain> ORDER
            = Comparator.comparing(BlockedDomain::getDomainName)
                    .thenComparing(BlockedDomain::getDateTime)
                    .thenComparing(BlockedDomain::getIsBlocked);

    @Override
    public int compare(BlockedDomain d1, BlockedDomain d2) {
        return ORDER.compare(d1, d2);
    }
}

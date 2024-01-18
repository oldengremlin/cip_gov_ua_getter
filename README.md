cip.gov.ua-getter
=================

Що ми тут робимо?
-----------------

Читаємо та аналізуємо
<a href="https://cip.gov.ua/ua/filter?tagId=60751" class="uri">розпорядження НЦУ</a>,
адже є
<a href="https://nkrzi.gov.ua/index.php?r=site/index&amp;pg=99&amp;id=2905&amp;language=uk" class="uri">розпорядження НКРЗІ</a>
яке зобов'язує провайдерів щоденно здійснювати моніторинг та виконання
розпоряджень НЦУ.

На виході формуємо з доступної теккстової інформації, щодо заблокованих
ресурсів, список доменів, які блокуються на рівні DNS.

Чого хотілося б?
-----------------

Хотілося б щоб НЦУ "викатило" нормальний API через який можна було б
просто зчитати актуальний список доменів, які треба блокувати на рівні
DNS.

Хотілося б щоб НЦУ "викатило" нормальний API через який можна було б
просто зчитати актуальний список AS, які треба блокувати на рівні BGP.

Хотілося б щоб НЦУ "викатило" нормальний API через який можна було б
просто зчитати актуальний список IPv4 та IPv6, які треба блокувати на
рівні L3.

Бо зараз кожен провайдер змушений самостійно вигадувати велосипед,
контроль за роботою якого НКРЗІ залишає за собою. Це нагадує: не знаємо
як, але не так! Є розпорядження - виконуйте! Як? Та хто ж вам скаже…


-   <a href="https://www.digitalocean.com/community/tutorials/jackson-json-java-parser-api-example-tutorial" class="uri">https://www.digitalocean.com/community/tutorials/jackson-json-java-parser-api-example-tutorial</a>
-   <a href="https://javarevisited.blogspot.com/2022/03/3-examples-to-parse-json-in-java-using.html" class="uri">https://javarevisited.blogspot.com/2022/03/3-examples-to-parse-json-in-java-using.html</a>

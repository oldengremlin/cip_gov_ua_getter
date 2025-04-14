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

Матеріали трохи не по основній темі, але для нотатків.
------------------------------------------------------

- [ ] <a href="https://www.digitalocean.com/community/tutorials/jackson-json-java-parser-api-example-tutorial" class="uri">https://www.digitalocean.com/community/tutorials/jackson-json-java-parser-api-example-tutorial</a>
- [ ] <a href="https://javarevisited.blogspot.com/2022/03/3-examples-to-parse-json-in-java-using.html" class="uri">https://javarevisited.blogspot.com/2022/03/3-examples-to-parse-json-in-java-using.html</a>

cip_gov_ua_getter
------------------------------------------------------
Консольна утиліта для збору розпоряджень про блокування доменів із сайту cip.gov.ua.
Встановлення

Встановіть Java 16+ і Maven.
Склонуйте репозиторій:git clone <repository_url>


Встановіть залежності:mvn clean install



Налаштування
Створіть файл cip.gov.ua.properties у корені проекту:
# URL для API зі списком розпоряджень
urlArticles=https://cip.gov.ua/services/cm/api/articles?page=0&size=1000&tagId=60751
# URL для завантаження вкладень
urlPrescript=https://cip.gov.ua/services/cm/api/attachment/download?id=
# Вхідні файли з доменами (через ;)
blocked=blocked.txt;blocked.ncu
# Вихідний файл із результуючим списком доменів
blocked_result=blocked.result.txt
# Папка для збереження вкладень
store_prescript_to=./Prescript
# HTTP User-Agent для запитів
userAgent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36
# Sec-Ch-Ua для JavaScript-запитів
secChUa="Chromium";v="129", "Not:A-Brand";v="24", "Google Chrome";v="129"

Збірка
Використовуйте Maven для створення fat JAR:
mvn clean package

Результат: target/cip_gov_ua_getter-2.0-all.jar.
Запуск
Помістіть logback.xml і cip.gov.ua.properties у поточну директорію та виконайте:
java -jar target/cip_gov_ua_getter-2.0-all.jar

Або вкажіть шлях до конфігурації:
java -jar target/cip_gov_ua_getter-2.0-all.jar /path/to/cip.gov.ua.properties

Для увімкнення дебаг-логів додайте --debug або -d:
java -jar target/cip_gov_ua_getter-2.0-all.jar --debug
java -jar target/cip_gov_ua_getter-2.0-all.jar /path/to/cip.gov.ua.properties -d

Логи
Логи записуються в logs/cip_gov_ua_getter.log. Невдалі запити зберігаються в failed_ids.txt.
Вихідні файли

Розпорядження: ./Prescript (або шлях із store_prescript_to)
Список доменів: blocked_result (задається в cip.gov.ua.properties)

Залежності

org.json:json:20231013
com.microsoft.playwright:playwright:1.51.0
commons-validator:commons-validator:1.9.0
ch.qos.logback:logback-classic:1.5.18


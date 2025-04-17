# cip.gov.ua-getter

## Що ми тут робимо?

Читаємо та аналізуємо розпорядження НЦУ, адже є розпорядження НКРЗІ, яке зобов’язує провайдерів щоденно моніторити та виконувати розпорядження НЦУ.

На виході формуємо з текстових даних список доменів, які блокуються на рівні DNS. Утиліта завантажує розпорядження через API, обробляє вкладення (TXT, PDF), валідує домени та формує `blocked.result.txt` із актуальними доменами для блокування.

Починаючи з версії 3.0, утиліта також парсить перелік сервісів держави-агресора з `webportal.nrada.gov.ua`, додаючи відповідні домени до `blocked.result.txt`.

## Чого хотілося б?

Хотілося б, щоб НЦУ надало нормальний API для:

- Актуального списку доменів для блокування на рівні DNS.
- Списку AS для блокування на рівні BGP.
- Списку IPv4 та IPv6 для блокування на рівні L3.

Поки що кожен провайдер змушений вигадувати свій велосипед, а НКРЗІ лише каже: "Є розпорядження — виконуйте!" Як? Ну, це вже ваші проблеми… 😅

## Про проект

`cip_gov_ua_getter` — консольна утиліта для збору розпоряджень про блокування доменів із сайту cip.gov.ua. Вона:

- Завантажує розпорядження через API (`articles` та `attachment/download`).
- Кешує вкладення локально в папці `PRESCRIPT`.
- Валідує домени, обробляє гомогліфи та пропускає IP-адреси.
- Формує список заблокованих доменів у `blocked.result.txt`.
- Підтримує дебаг-режим для детальних логів.
- Парсить перелік сервісів держави-агресора з `webportal.nrada.gov.ua` (з версії 3.0).

### Можливості (реліз 3.0)

- **Парсинг сервісів держави-агресора**: Витягує домени з PDF на `webportal.nrada.gov.ua` і додає їх до `blocked.result.txt`.
- **Оптимізація швидкості**: ~6 секунд для запусків із локальним кешем, ~30-35 хвилин для першого "чистого" запуску.
- **Блокування непотрібних ресурсів**: Ігноруються `.jpg`, `.jpeg`, `.png`, `.svg`, `.woff2`, `.css`, Google Analytics і Google Tag Manager для швидшого завантаження.
- **Гнучке логування**: Режим `-d` для дебаг-логів, чисті логи на `INFO` за замовчуванням.
- **Валідатор доменів**: Перевірка через `commons-validator`, обробка гомогліфів із `icu4j`.
- **Кешування**: Локальні файли зменшують кількість запитів до API.
- **Конфігурація**: Налаштування через `cip.gov.ua.properties` (шляхи, User-Agent, вхідні/вихідні файли, URL сервісів агресора).

## Встановлення

1. **Вимоги**:

   - Java 16+.
   - Maven 3.6+.
   - ~2 ГБ RAM (для Playwright).
   - Debian 11+ або інша ОС із підтримкою Chromium.

2. **Клонування репозиторію**:

   ```bash
   git clone https://github.com/oldengremlin/cip_gov_ua_getter.git
   cd cip_gov_ua_getter
   ```

3. **Встановлення залежностей**:

   ```bash
   mvn clean install
   ```

4. **Створення конфігурації**: Створіть файл `cip.gov.ua.properties` у корені проекту. Приклад:

   ```properties
   urlArticles=https://cip.gov.ua/services/cm/api/articles?page=0&size=1000&tagId=60751
   urlPrescript=https://cip.gov.ua/services/cm/api/attachment/download?id=
   blocked=blocked.txt;blocked.ncu
   blocked_result=blocked.result.txt
   store_prescript_to=./PRESCRIPT
   userAgent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36
   secChUa="Chromium";v="129", "Not:A-Brand";v="24", "Google Chrome";v="129"
   urlAggressorServices=https://webportal.nrada.gov.ua/perelik-servisiv-derzhavy-agresora/#perelik
   ```

## Використання

1. **Збірка JAR**:

   ```bash
   mvn clean package
   ```

2. **Запуск утиліти**:

   - Звичайний режим:

     ```bash
     java -jar target/cip_gov_ua_getter-3.0-all.jar
     ```

   - Дебаг-режим:

     ```bash
     java -jar target/cip_gov_ua_getter-3.0-all.jar -d
     ```

   - Вказати свій конфіг:

     ```bash
     java -jar target/cip_gov_ua_getter-3.0-all.jar path/to/your.properties
     ```

3. **Результати**:

   - Вкладення зберігаються в `store_prescript_to` (наприклад, `./PRESCRIPT`).
   - Список доменів, включно з сервісами агресора, — у `blocked.result.txt`.
   - Логи — у `logs/cip_gov_ua_getter.log`.

### Приклад вихлопу

- `blocked.result.txt`:

  ```
  example.com
  test.org
  xn--80ak6aa92e.com
  aggressor-service.ru
  ```

- Лог із `-d`:

  ```
  2025-04-16 10:00:00 INFO  n.u.cip_gov_ua_getter - Fetching prescript for ID 68502 from server
  2025-04-16 10:00:00 DEBUG n.u.cip_gov_ua_getter - Blocked static resource: https://cip.gov.ua/content/css/loading.css
  2025-04-16 10:00:00 INFO  n.u.cip_gov_ua_getter - Successfully downloaded PDF to: /home/olden/.../MANUAL/Perelik.#450.2023.07.06.pdf
  2025-04-16 10:00:01 INFO  n.u.cip_gov_ua_getter - Successfully stored blocked domains to blocked.result.txt
  ```

## Нотатки для розробників

- **Залежності**:

  - Playwright 1.51.0 (Apache License 2.0)
  - commons-validator 1.9.0 (Apache License 2.0)
  - icu4j 77.1 (Unicode License)
  - logback-classic 1.5.18 (EPL 1.0/LGPL 2.1)
  - json 20250107 (JSON License)
  - pdfbox 3.0.3 (Apache License 2.0)
  - jsoup 1.18.1 (MIT License)

- **Логіка роботи**:

  - `CGUGetter`: Завантажує JSON із розпорядженнями.
  - `GetPrescript`: Завантажує/читає вкладення, валідує домени.
  - `AggressorServicesParser`: Парсить PDF із сервісами держави-агресора.
  - `BlockedObjects`: Формує список заблокованих доменів.
  - `BlockedDomain`/`BlockedDomainComparator`: Зберігає та сортує домени.

- **Майбутні ідеї**:

  - Кешування JSON для зменшення запитів до API.
  - Паралельна обробка вкладень (з обережністю через ризик блокування).
  - Додавання підтримки `.woff`, `.ttf` до блокування ресурсів.

- **Посилання для вивчення**:

  - Jackson JSON Parser
  - JSON Parsing in Java

## Відомі проблеми

- Перший запуск може тривати ~30-35 хвилин через завантаження всіх вкладень.
- Потрібна стабільна мережа, інакше запити можуть завершитися помилкою (записуються в `failed_ids.txt`).
- Debian 11 видає попередження про застарілий WebKit. Рекомендується оновити ОС.

## Ліцензія

Apache License 2.0. Див. [LICENSE](LICENSE) та [NOTICE](NOTICE).  
Пояснення українською: [LICENSE-UKR.md](LICENSE-UKR.md).

## Контакти

Питання, баги, ідеї? Відкривайте [issues](https://github.com/oldengremlin/cip_gov_ua_getter/issues) в репозиторії.

---

**Version 3.1 (2025-04-16)**
- Уніфіковано механізм перевірки знайдений доменних імен як для розпоряджень НЦУ так і для тих, що знайдено в переліку сервісів держави-агресора.
- Проведено інші оптимізації в коді.

**Version 3.0 (2025-04-16)**

- Додано парсинг сервісів держави-агресора з `webportal.nrada.gov.ua`, домени додаються до `blocked.result.txt`.
- Уніфіковано обробку гомогліфів із використанням `SpoofChecker`.

**Version 2.4 (2025-04-16)**

- Додано блокування статичних ресурсів (`.jpg`, `.jpeg`, `.png`, `.svg`, `.woff2`, `.css`).
- Покращено продуктивність: ~6 секунд для кешованих запусків.
- Додано дебаг-режим із детальними логами (`-d`).
- Оптимізовано логування: чисті логи на `INFO`, деталі на `DEBUG`.

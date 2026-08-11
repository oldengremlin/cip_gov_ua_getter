# Changelog

Формат на основі [Keep a Changelog](https://keepachangelog.com/uk/1.1.0/),
версіонування — [Semantic Versioning](https://semver.org/lang/uk/).

## [3.2.1] — 2026-08-11

### Виправлено

- **`AggressorServicesParser` роками парсив застарілий PDF.** Сайт
  `webportal.nrada.gov.ua` перевидає перелік сервісів держави-агресора під
  новим датованим іменем файлу щоразу, коли його оновлює, а локальний кеш
  зберігався під одним фіксованим іменем
  (`AggressorServices_PRIMARY_PDF_NAME`). `downloadPdf()` бачив, що файл під
  цим іменем уже існує, і не завантажував нічого — тож жодне оновлення
  переліку з моменту першого запуску так і не потрапляло в
  `blocked.result.txt`. Виявлено після того, як перевірка НКЕК зафіксувала
  незаблоковані домени (`rezka-ua.pub`, `filmix.my`, `oneliketv.net`,
  `vits.tv`, `magtv.top`), присутні в оновленому переліку від 25.06.2026.

### Додано

- **Кеш переліку сервісів держави-агресора тепер має термін придатності.**
  Нова властивість `AggressorServices_max_age_hours` (типово `24`) визначає,
  через скільки годин локальна копія PDF вважається застарілою й потребує
  оновлення. Якщо сервер під час спроби оновлення недоступний — джерело не
  провалюється: використовується наявна застаріла копія, у лог пишеться
  `WARN`. Логи тепер чітко розрізняють три випадки: `Downloaded fresh PDF`,
  `Cached PDF is still fresh … skipping download` і `Failed to refresh stale
  cached PDF, falling back to on-disk copy`.
- `AbstractPDFParser.downloadPdf()` / `downloadAndExtractAll()` отримали
  параметр `Duration maxAge` (`null` — кешувати назавжди, як і раніше для
  рішень НКЕК у `PlaycityParser`, де кожен документ незмінний і має власний
  URL).

### Змінено

- `Files.move()` під час заміни кешованого PDF тепер завжди поєднує
  `ATOMIC_MOVE` і `REPLACE_EXISTING` в одному виклику (раніше `REPLACE_EXISTING`
  застосовувався лише у фолбеку на `AtomicMoveNotSupportedException`, тож
  заміна вже існуючого файлу без цього фолбеку завершилася б
  `FileAlreadyExistsException`).

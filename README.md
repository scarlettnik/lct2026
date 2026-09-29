# TeploKontur

TeploKontur — Java 11 / Spring Boot 2.6.3 сервис для автоматического построения вариантов подключения перспективных объектов капитального строительства (ОКС) к существующей тепловой сети.

Сервис принимает GeoJSON в WGS84, потоково загружает его в PostgreSQL, переводит геометрию в EPSG:32637 для метрических расчётов, строит варианты новой тепловой сети в режимах 2D и 3D, рассчитывает расходы и диаметры, стоимость и конкурсный показатель `S`, независимо валидирует решения и экспортирует результат обратно в GeoJSON.


## Быстрый расчёт и уточнения участников (29.09.2026)

По умолчанию выполняются три быстрых старта: `competition.optimizer.max-starts=3`, `refine=true`, с отбором трёх содержательно разных допустимых вариантов. Проверки исходной геометрии, расходов, ДУ и стоимости выполняются полностью. Для более длительного поиска альтернатив можно увеличить `max-starts` (до 8) ; для минимального времени можно отключить `refine`; на произвольном входе число вариантов может быть меньше трёх, если допустимых различий недостаточно. Показатель S ранжирует варианты внутри решения.

Ближайший допустимый внешний фасад проверяется первым. Если подход не найден, рассматриваются следующие фасады. Конечный ввод остаётся прямым, без транзита через здание; перпендикулярность не требуется. Рабочие контуры могут упрощаться, но проверки используют исходную геометрию. ДУ сохраняется на всей непрерывной части с неизменным расходом.

В GeoJSON `heat_network.cost` содержит стоимость строительства участка, а дополнительное поле `tie_in_cost` — стоимость его врезки в существующую камеру (5 млн руб. на новый участок). Для суммы объектов нужно учитывать оба поля. В сводке эти статьи разделены. Новая камера на существующей трубе оплачивается единожды через стоимость камеры.

---

## 1. Технологический стек

| Компонент | Реализация |
|---|---|
| Java | 11 |
| Spring Boot | 2.6.3 |
| Spring Web | REST API |
| Spring Data JPA | persistence layer |
| PostgreSQL | 15 |
| Maven | 3.8+ |
| JTS | 1.19.0 |
| Proj4J | 1.2.3 |
| springdoc-openapi-ui | 1.7.0 |
| JUnit 5 | автоматические тесты |
| Docker | multi-stage build |
| Docker Compose | backend + PostgreSQL |

Входной и выходной GeoJSON использует WGS84 / EPSG:4326. Все длины и расстояния рассчитываются в UTM zone 37N / EPSG:32637.

---

## 2. Назначение системы

Система выполняет полный цикл расчёта:

1. принимает GeoJSON-сценарий;
2. валидирует структуру и ссылки объектов;
3. сохраняет данные сценария в PostgreSQL;
4. восстанавливает недостающую топологию существующей тепловой сети;
5. формирует кандидаты точек подключения;
6. строит допустимые маршруты с учётом ограничений;
7. допускает совместное использование новых магистральных участков несколькими ОКС;
8. агрегирует расходы;
9. назначает минимально допустимые DN;
10. оптимизирует топологию, положения камер и точек врезки;
11. в 3D рассчитывает профиль глубины;
12. вычисляет стоимость, длину и показатель `S`;
13. выполняет независимую инженерную проверку;
14. выбирает до трёх содержательно различающихся решений;
15. экспортирует итоговые GeoJSON.

---

# 3. Как запустить проект

## 3.1. Быстрый запуск одной командой

Требуется:

- Docker;
- Docker Compose v2 или `docker-compose`;
- Bash;
- `curl` с поддержкой `--fail-with-body`;
- `jq`.

Из корня проекта:

```bash
chmod +x RUN_TASK.sh
./RUN_TASK.sh 'task/Датасет скорректированный.geojson' results
```

Скрипт автоматически:

1. собирает актуальный Java-код;
2. запускает PostgreSQL;
3. запускает Spring Boot backend;
4. ждёт готовности API;
5. загружает GeoJSON;
6. запускает расчёт 2D;
7. ждёт `DONE`;
8. сохраняет варианты и GeoJSON;
9. запускает расчёт 3D;
10. ждёт `DONE`;
11. сохраняет варианты и GeoJSON;
12. проверяет количество вариантов, их validation status и подключение ОКС.

После выполнения каталог `results` содержит примерно:

```text
results/
├── scenario.json
├── run-2d.json
├── run-3d.json
├── status-2d.json
├── status-3d.json
├── variants-2d.json
├── variants-3d.json
├── result-2d.geojson
├── result-3d.geojson
├── result-2d-2d_1.geojson
├── result-2d-2d_2.geojson
├── result-2d-2d_3.geojson
├── result-3d-3d_1.geojson
├── result-3d-3d_2.geojson
└── result-3d-3d_3.geojson
```

Для произвольного входного набора количество вариантов может быть меньше трёх, если невозможно построить три инженерно допустимых и достаточно различающихся решения.

## 3.2. Если порт 8080 занят

```bash
APP_PORT=18080 \
./RUN_TASK.sh 'task/Датасет скорректированный.geojson' results
```

## 3.3. Допустить частичный результат

```bash
MIN_VARIANTS=1 REQUIRE_CONNECTED=0 \
./RUN_TASK.sh input.geojson results-custom
```

## 3.4. Использовать уже запущенный backend

```bash
SKIP_DOCKER=1 \
BASE_URL=http://localhost:8080/api/v1 \
./RUN_TASK.sh input.geojson results
```

---

# 4. Запуск через Docker Compose

## 4.1. Сборка и запуск

```bash
docker compose up --build -d
```

Проверить контейнеры:

```bash
docker compose ps
```

Посмотреть логи приложения:

```bash
docker compose logs -f app
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

API:

```text
http://localhost:8080/api/v1
```

## 4.2. Остановка

С сохранением PostgreSQL volume:

```bash
docker compose down
```

С удалением volumes:

```bash
docker compose down -v
```

## 4.3. Память JVM

В `Dockerfile` задано:

```text
-Xms256m -Xmx2g
```

Переопределение:

```bash
JAVA_TOOL_OPTIONS='-Xms512m -Xmx4g' \
docker compose up --build -d
```

---

# 5. Локальный запуск через Maven

## 5.1. Требования

- JDK 11+;
- Maven 3.8+;
- PostgreSQL;
- свободный порт 8080.

Проверка:

```bash
java -version
mvn -version
```

## 5.2. PostgreSQL в Docker

```bash
docker run -d \
  --name teplokontur-local-db \
  -p 127.0.0.1:5432:5432 \
  -e POSTGRES_DB=teplokontur \
  -e POSTGRES_USER=teplo \
  -e POSTGRES_PASSWORD=teplo \
  postgres:15
```

## 5.3. Сборка

Полная:

```bash
mvn clean package
```

Без тестов:

```bash
mvn -DskipTests clean package
```

Результат:

```text
target/teplokontur-1.0.0.jar
```

## 5.4. Запуск JAR

```bash
java -Xms256m -Xmx2g \
  -jar target/teplokontur-1.0.0.jar
```

## 5.5. Другая БД

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/teplokontur'
export SPRING_DATASOURCE_USERNAME='teplo'
export SPRING_DATASOURCE_PASSWORD='teplo'

java -jar target/teplokontur-1.0.0.jar
```

---

# 6. REST API

## 6.1. Загрузка сценария multipart/form-data

```bash
curl --fail-with-body \
  -F 'file=@task/Датасет скорректированный.geojson' \
  http://localhost:8080/api/v1/scenarios
```

Пример ответа:

```json
{
  "scenario_id": 1,
  "name": "Датасет скорректированный.geojson",
  "status": "READY",
  "feature_count": 123,
  "diagnostic": null
}
```

Multipart-запрос должен содержать ровно одну file-part с именем `file`.

## 6.2. Потоковая загрузка raw body

```bash
curl --fail-with-body \
  -H 'Content-Type: application/geo+json' \
  -H 'X-Filename: scenario.geojson' \
  --data-binary '@input.geojson' \
  http://localhost:8080/api/v1/scenarios
```

Дополнительно поддерживаются `application/json` и `application/octet-stream`.

Максимальный размер загрузки — 3 GiB.

## 6.3. Проверка сценария

```bash
curl --fail-with-body \
  http://localhost:8080/api/v1/scenarios/1/validation
```

## 6.4. Запуск 2D

```bash
curl --fail-with-body -X POST \
  'http://localhost:8080/api/v1/scenarios/1/runs?mode=2d'
```

## 6.5. Запуск 3D

```bash
curl --fail-with-body -X POST \
  'http://localhost:8080/api/v1/scenarios/1/runs?mode=3d'
```

Значение `depth` также переводится в режим 3D.

## 6.6. Получить статус

```bash
curl --fail-with-body \
  http://localhost:8080/api/v1/runs/5
```

Статусы:

- `QUEUED`;
- `RUNNING`;
- `DONE`;
- `ERROR`.

Фазы:

- `QUEUED`;
- `LOAD`;
- `OPTIMIZE`;
- `EXPORT`;
- `DONE`;
- `ERROR`.

## 6.7. Получить варианты

```bash
curl --fail-with-body \
  http://localhost:8080/api/v1/runs/5/variants
```

В summary каждого варианта присутствуют:

- `variant_id`;
- `rank`;
- `policy`;
- `mode`;
- `score`;
- `calculated_cost`;
- `length`;
- `connected`;
- `total`;
- `validation_passed`;
- `validation_errors`;
- `validation_warnings`.

## 6.8. Один вариант

```bash
curl --fail-with-body \
  http://localhost:8080/api/v1/runs/5/variants/3d_1
```

## 6.9. Валидация варианта

```bash
curl --fail-with-body \
  http://localhost:8080/api/v1/runs/5/variants/3d_1/validation
```

## 6.10. Экспорт всех вариантов

```bash
curl --fail-with-body \
  http://localhost:8080/api/v1/runs/5/export \
  -o result.geojson
```

## 6.11. Экспорт одного варианта

```bash
curl --fail-with-body \
  http://localhost:8080/api/v1/runs/5/variants/3d_1/export \
  -o result-3d_1.geojson
```

---

# 7. Обработка ошибок API

Ошибки входных данных возвращают HTTP 422:

```json
{
  "error": "INPUT_VALIDATION_ERROR",
  "message": "..."
}
```

Внутренние ошибки:

```json
{
  "error": "INTERNAL_ERROR",
  "message": "..."
}
```

Если exception возник внутри асинхронной оптимизации, run переводится в `ERROR`, а текст ошибки доступен в поле `error`.

---

# 8. Формат входных данных

Корень должен иметь формат:

```json
{
  "type": "FeatureCollection",
  "features": []
}
```

Каждый элемент — GeoJSON `Feature`.

Обязательные свойства:

- `properties.id`;
- `properties.object_type`.

Основные типы:

| `object_type` | Geometry | Назначение |
|---|---|---|
| `source` | Point | источник сети |
| `heat_network` | LineString | существующая тепловая сеть |
| `heat_chamber` | Point | существующая камера |
| `oks_future` | Polygon / MultiPolygon | перспективный ОКС |
| `oks_connection_point` | Point | точка подключения ОКС |
| `oks_existing` | Polygon / MultiPolygon | существующее здание |
| `restriction` | Geometry | территориальное ограничение |

Дополнительные свойства:

- `restriction_type`;
- `diameter`;
- `flow_tph`;
- `upstream_object_id`;
- `oks_id`.

ID могут быть строковыми, числовыми или boolean и приводятся к строке.

## 8.1. Что проверяется при загрузке

Система проверяет:

- валидность JSON;
- корневой `FeatureCollection`;
- наличие `features[]`;
- `Feature` для каждого элемента;
- ID;
- `object_type`;
- соответствие geometry типу объекта;
- валидность JTS geometry;
- известный `restriction_type`;
- уникальность ID внутри сценария;
- ровно один `source`;
- корректность `oks_id`;
- корректность существующей upstream-топологии.

## 8.2. Потоковая загрузка

`GeoJsonStreamingIngestor` не читает весь файл в память.

Обработка:

```text
HTTP stream
→ Jackson parser
→ один Feature
→ validation
→ WGS84 → UTM
→ FeatureEntity
→ batch до 200 объектов
→ saveAllAndFlush
→ EntityManager.clear()
```

Это позволяет принимать большие файлы без хранения полного GeoJSON в Java heap.

---

# 9. Система координат

Вход:

```text
EPSG:4326 / WGS84
[longitude, latitude]
```

Внутренние расчёты:

```text
EPSG:32637
UTM zone 37N
метры
```

2D экспорт:

```text
[longitude, latitude]
```

3D экспорт:

```text
[longitude, latitude, -depth]
```

Z — относительная отметка относительно условной поверхности `Z=0`, а не абсолютная геодезическая высота.

---

# 10. Архитектура приложения

Базовый package:

```text
ru.lct.teplokontur
```

| Package | Назначение |
|---|---|
| `api` | REST controllers и exception handling |
| `config` | properties и async executor |
| `domain` | доменные модели и нормативные таблицы |
| `engineering` | расходы, DN, стоимость, профиль глубины |
| `export` | GeoJSON export |
| `ingest` | streaming ingest и CRS |
| `optimization` | построение и оптимизация |
| `persistence` | JPA entities и repositories |
| `routing` | геометрическая маршрутизация |
| `service` | orchestration |
| `validation` | независимая проверка |

Главная точка входа:

```text
ru.lct.teplokontur.TeploKonturApplication
```

---

# 11. Жизненный цикл расчёта

```text
GeoJSON
   ↓
POST /api/v1/scenarios
   ↓
streaming ingest + validation
   ↓
PostgreSQL
   ↓
scenario READY
   ↓
POST /api/v1/scenarios/{id}/runs?mode=2d|3d
   ↓
QUEUED
   ↓
LOAD
   ↓
OPTIMIZE
   ↓
EXPORT
   ↓
OutputGeoJsonValidator
   ↓
DONE / ERROR
```

`OptimizationService.execute()` запускается асинхронно через `optimizerExecutor`.

Пул:

- core pool size: 1;
- max pool size: 2;
- queue capacity: 100.

---

# 12. Основные Java-компоненты

## `ScenarioController`

Принимает multipart и raw GeoJSON.

## `GeoJsonStreamingIngestor`

Потоково разбирает и сохраняет входные объекты.

## `SnapshotLoader`

Формирует `InputSnapshot` из PostgreSQL.

## `ExistingTopologyResolver`

Восстанавливает отсутствующие upstream-ссылки по геометрии существующих труб.

## `OptimizationService`

Управляет жизненным циклом run.

## `UniversalOptimizer`

Главный orchestration алгоритма.

## `SharedNetworkBuilder`

Строит лес новой сети с возможностью переиспользования уже построенных trunk-участков.

## `SparseVisibilityRouter`

Основной production router.

## `RouteConstraintEngine`

Проверяет геометрические и инженерные ограничения каждого сегмента.

## `JointPlanRefiner`

Совместно оптимизирует положения камер и точек врезки.

## `JointChamberMilp`

Точный top-k решатель дискретной tree-задачи выбора положений узлов.

## `PlanEvaluator`

Пересчитывает DN, стоимость, длину и `S`.

## `EngineeringValidator`

Независимо проверяет plan.

## `GeoJsonResultWriter`

Формирует итоговый GeoJSON.

## `OutputGeoJsonValidator`

Проверяет уже записанный результат.

---

# 13. Краткое описание алгоритма

```text
Input GeoJSON
    ↓
Streaming ingest
    ↓
Validation
    ↓
WGS84 → UTM
    ↓
Snapshot + topology recovery
    ↓
Tie candidates
    ↓
Shared network construction
    ↓
Sparse visibility routing
    ↓
Flow aggregation
    ↓
DN sizing
    ↓
Cost + S
    ↓
Junction refinement
    ↓
Joint chamber/tie optimization
    ↓
Leaf reconnection
    ↓
Local search
    ↓
Engineering validation
    ↓
Diversity selection
    ↓
GeoJSON export
    ↓
Output validation
```

Полное описание приведено в `ALGORITHM.md`.

---

# 14. Геометрические ограничения

Маршрутизатор учитывает:

- существующие здания;
- парки;
- социальные территории;
- запрещённые площадки;
- водные объекты;
- железную дорогу;
- дороги;
- трамвайные пути;
- газопроводы;
- силовые кабели;
- существующую тепловую сеть;
- уже построенную новую сеть.

Основные инварианты:

- транзит через здания запрещён;
- разрешён только специальный финальный ввод в собственный ОКС;
- поворот более 90° запрещён;
- новые участки не пересекаются друг с другом вне общей вершины;
- overlap с существующей сетью запрещён;
- пересечения допустимых коммуникаций проверяются отдельно.

---

# 15. Правило входа в здание

Если terminal находится внутри `oks_existing`:

1. определяется внешний контур здания;
2. находится минимальное расстояние terminal → внешний фасад;
3. рассматриваются все равноудалённые ближайшие стены;
4. строится прямой ввод через ближайшую стену;
5. точка подхода выносится за required clearance;
6. дальнейший маршрут идёт снаружи;
7. внутренние дворы не считаются внешним фасадом;
8. вход через дальнюю стену отклоняется;
9. повторный вход в здание запрещён.

Правило одинаково для 2D и 3D.

---

# 16. Расходы и диаметры

Расход terminal агрегируется вверх по дереву новой сети.

Для ребра:

```text
flow(edge) = сумма расходов downstream terminal
```

Минимальный DN выбирается из `RuleBook.DN` по пропускной способности.

Также проверяется максимальная допустимая длина непрерывной части с одинаковым расчётным расходом.

Диаметр не должен уменьшаться по направлению от потребителя к существующей сети.

---

# 17. Стоимость и score

Штраф неподключённого ОКС:

```text
P(flow) = 100 000 000 + 500 000 × flow
```

Итоговая стоимость текущей модели:

```text
C =
    стоимость новых труб
  + стоимость новых камер
  + стоимость подключений к существующим камерам
  + штраф за неподключённые ОКС
```

Реконструкция существующей сети в текущем `PlanEvaluator` отключена:

```text
reconstruction_cost = 0
chamber_reconstruction_cost = 0
reconstruction_length = 0
```

Конкурсная формула:

```text
S = 0.7 × C / 25 000 000
  + 0.3 × L / 100
```

Меньшее значение `S` лучше.

---

# 18. 2D и 3D

## 18.1. 2D

Маршрут рассчитывается в плане.

Экспорт содержит координаты `[longitude, latitude]`.

## 18.2. 3D

3D запускается как отдельная оптимизация.

Основные ограничения:

- нормальная глубина: 3 м;
- минимум: 0.7 м;
- максимальный уклон: 0.10;
- возврат к глубине 3 м в узлах;
- plateau длиной 4 м на пересечениях коммуникаций;
- выбор прохода сверху, если хватает clearance и длины рамп;
- иначе проход снизу.

Depth multiplier:

```text
depthFactor(d) =
    1.0,                   d <= 3
    1 + 0.10 × (d - 3),    d > 3
```

---

# 19. Формирование вариантов

Optimizer запускает несколько deterministic стартов.

Для каждого строится исходная топология, затем выполняются:

- `JunctionRefiner`;
- `JointPlanRefiner`;
- archive;
- reconnect leaves;
- `PlanLocalSearch`;
- повторный joint refinement.

В 2D выполняется до 2 reconnect rounds, в 3D — до 3.

Финальные кандидаты сравниваются по diversity.

Различие учитывает:

1. tie-in points;
2. grouping / topology;
3. геометрические коридоры.

Выбирается до трёх попарно достаточно различающихся вариантов.

---

# 20. Валидация

`EngineeringValidator` проверяет:

- отсутствие циклов;
- корректность roots и parents;
- степень узлов ≤4;
- степень существующих камер;
- connected + unconnected = все исходные ОКС;
- расходы;
- минимально допустимые DN;
- max-length ограничения;
- совпадение концов geometry с nodes;
- запретные территории;
- clearance;
- crossing rules;
- turn ≤90°;
- пересечения новой сети;
- 3D profile;
- стоимость;
- длину;
- score.

`OutputGeoJsonValidator` дополнительно проверяет экспортированный файл:

- `FeatureCollection`;
- обязательные поля;
- допустимые output object types;
- координаты;
- размерность 2D/3D;
- соответствие Z полям depth;
- уникальность ID;
- суммы стоимости;
- суммы длин;
- один summary на variant;
- официальный score.

---

# 21. Формат результата

Текущие output object types:

| object_type | Назначение |
|---|---|
| `heat_network` | новый участок сети |
| `heat_chamber` | новая камера |
| `technical_node` | техническая точка |
| `variant_summary` | сводка варианта |

Для нового участка сети экспортируются:

- `id`;
- `variant_id`;
- `start_node_id`;
- `end_node_id`;
- `flow_tph`;
- `diameter`;
- `length`;
- `laying_method`;
- `depth_start`;
- `depth_end`;
- `cost`;
- geometry.

`variant_summary` содержит:

- rank;
- construction cost;
- chamber construction cost;
- tie-in cost;
- reconstruction totals;
- unconnected penalty;
- calculated cost;
- new network length;
- total length;
- score;
- `unconnected_oks_ids`.

---

# 22. Конфигурация

Файл:

```text
src/main/resources/application.yml
```

Routing:

| Параметр | Default |
|---|---:|
| `competition.routing.grid-step-m` | 3.0 |
| `competition.routing.corridor-padding-m` | 120.0 |
| `competition.routing.max-expanded-cells` | 900000 |
| `competition.routing.working-set-padding-m` | 5000 |

Optimizer:

| Параметр | Default |
|---|---:|
| `competition.optimizer.beam-width` | 24 |
| `competition.optimizer.tie-candidates` | 8 |
| `competition.optimizer.k-paths` | 4 |
| `competition.optimizer.random-seed` | 20260922 |

Основной production router — `SparseVisibilityRouter`.

---

# 23. Persistence

Основные сущности:

## `ScenarioEntity`

- ID;
- name;
- status;
- createdAt;
- featureCount;
- diagnostic.

## `FeatureEntity`

- scenario ID;
- feature ID;
- object type;
- restriction type;
- WKB UTM geometry;
- исходные properties;
- исходная geometry JSON;
- bbox;
- diameter;
- flow;
- upstream ID;
- OKS ID.

## `RunEntity`

- run ID;
- scenario ID;
- mode;
- status;
- progress;
- phase;
- result path;
- summary JSON;
- error.

Hibernate использует `ddl-auto: update`.

---

# 24. Тестирование

Полный test suite:

```bash
mvn test
```

Статическая проверка:

```bash
./scripts/verify_project.sh
```

`verify_project.sh` проверяет:

- Java 11;
- Spring Boot 2.6.3;
- springdoc 1.7.0;
- отсутствие известных dataset-specific marker в production source;
- отсутствие синтаксиса Java новее 11;
- компиляцию core domain classes;
- наличие Docker/config файлов.

Ключевые тесты:

- `DatasetQualityTest`;
- `GeneralizationSmokeTest`;
- `SparseVisibilityRouterTest`;
- `RouteConstraintEngineTest`;
- `DepthProfileTest`;
- `JointChamberMilpTest`;
- `PlanLocalSearchTest`;
- `VariantDiversityTest`;
- `GeoJsonResultWriterTest`.

---

# 25. Воспроизводимость и отсутствие hardcode

Production search строит решения только из `InputSnapshot`.

Не используются:

- заранее подготовленные result GeoJSON;
- фиксированные координаты;
- известные ID конкурсного набора;
- Python subprocess;
- внешний оптимизационный solver.

При одинаковых:

- входе;
- версии кода;
- `application.yml`;
- random seed

алгоритмический поиск воспроизводим.

Database ID сценария/run могут отличаться.

---

# 26. Производительность и память

В проекте используются:

- streaming JSON parsing;
- batch persistence по 200 объектов;
- `EntityManager.clear()`;
- bbox filtering;
- JTS `STRtree`;
- `PreparedGeometry`;
- `IndexedFacetDistance`;
- cache forbidden geometry по DN;
- cache segment assessments;
- scenario-level visibility graph cache;
- cache baseline routes;
- streaming export.

`SparseVisibilityRouter` ограничивает static obstacle candidate set значением 1600. При превышении выполняется пространственно распределённый radial sampling.

---

# 27. Troubleshooting

## API не запускается

```bash
docker compose ps
docker compose logs app
docker compose logs db
```

## Порт 8080 занят

```bash
APP_PORT=18080 docker compose up --build -d
```

## Ошибка подключения к БД

Проверьте:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
```

## Run долго остаётся `RUNNING`

```bash
curl http://localhost:8080/api/v1/runs/RUN_ID
docker compose logs -f app
```

Сложный расчёт может включать несколько initial topologies, routing, DN recalculation, joint refinement и повторную полную validation.

## `RUN_TASK.sh` завершился по timeout

```bash
MAX_POLLS=1440 POLL_SECONDS=10 \
./RUN_TASK.sh input.geojson results
```

## На другом датасете меньше трёх вариантов

```bash
MIN_VARIANTS=1 ./RUN_TASK.sh input.geojson results
```

Это допустимо: по уточнениям участников сервис возвращает до трёх вариантов. Быстрый режим по умолчанию ищет три содержательно разных варианта.

---

# 28. Структура проекта

```text
.
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── RUN_TASK.sh
├── README.md
├── ALGORITHM.md
├── REQUIREMENTS_TRACEABILITY.md
├── SPEC.md
├── scripts/
│   └── verify_project.sh
├── src/
│   ├── main/
│   │   ├── java/ru/lct/teplokontur/
│   │   └── resources/application.yml
│   └── test/java/ru/lct/teplokontur/
└── task/
    ├── Датасет скорректированный.geojson
    ├── Техническое приложение.docx
    └── 2. ДИТ.pdf
```

---

# 29. Дополнительная документация

- `ALGORITHM.md` — подробное техническое приложение по алгоритму;
- `REQUIREMENTS_TRACEABILITY.md` — связь требований с реализацией;
- `SPEC.md` — внутренние constraints и invariants;
- `/swagger-ui.html` — интерактивная документация REST API.

---

# 30. Рекомендуемые команды проверки

Полный end-to-end:

```bash
./RUN_TASK.sh 'task/Датасет скорректированный.geojson' results
```

Для разработки:

```bash
mvn test
./scripts/verify_project.sh
docker compose up --build -d
```
"""

algorithm = """# Техническое приложение: подробное описание алгоритма TeploKontur

## 1. Назначение документа

Документ описывает фактический алгоритм, реализованный в Java-проекте TeploKontur.

Основной orchestration-класс:

```text
ru.lct.teplokontur.optimization.UniversalOptimizer
```

Основной маршрутизатор:

```text
ru.lct.teplokontur.routing.SparseVisibilityRouter
```

Документ охватывает полный путь от входного GeoJSON до выбора и экспорта финальных вариантов.

---

# 2. Постановка задачи

Входной сценарий содержит:

- источник существующей тепловой сети;
- существующие трубы;
- существующие камеры;
- перспективные ОКС;
- точки подключения ОКС;
- существующие здания;
- пространственные ограничения.

Необходимо автоматически построить до трёх допустимых вариантов новой тепловой сети.

Для каждого варианта требуется определить:

1. точки присоединения к существующей сети;
2. топологию новой сети;
3. общие trunk-участки;
4. новые камеры;
5. геометрию труб;
6. диаметры;
7. расходы;
8. в 3D — профиль глубины;
9. стоимость;
10. длину;
11. конкурсный показатель `S`.

---

# 3. Общая схема

```text
1. HTTP upload
2. Streaming parsing
3. Input validation
4. WGS84 → UTM
5. Persistence
6. Snapshot loading
7. Existing topology recovery
8. Tie candidate generation
9. Shared forest construction
10. Sparse visibility routing
11. Flow aggregation
12. DN sizing
13. Cost and score evaluation
14. Junction refinement
15. Joint chamber/tie optimization
16. Leaf reconnection
17. Local geometry search
18. Repeated validation
19. Candidate archive
20. Diversity analysis
21. Portfolio selection
22. GeoJSON export
23. Output validation
```

2D и 3D являются независимыми run.

---

# 4. Потоковый ingest

Класс:

```text
GeoJsonStreamingIngestor
```

Максимальный размер:

```text
3 GiB
```

Алгоритм:

```text
create Scenario(status=INGESTING)

open Jackson JsonParser

require root object

for each root field:
    if type:
        require FeatureCollection

    if features:
        for each Feature:
            parse one feature
            validate
            convert geometry to UTM
            persist in current batch

            if batch size == 200:
                saveAllAndFlush
                EntityManager.clear()

require exactly one source
validate references
set scenario READY
```

Файл не загружается целиком в Java heap.

---

# 5. Валидация входных объектов

Для каждого Feature проверяется:

```text
Feature.type == "Feature"
properties.id exists
properties.object_type exists
geometry is valid
geometry type matches object_type
restriction_type is known
required fields exist
```

Типы geometry:

| object_type | geometry |
|---|---|
| `source` | Point |
| `heat_chamber` | Point |
| `oks_connection_point` | Point |
| `heat_network` | LineString |
| `oks_future` | Polygon / MultiPolygon |
| `oks_existing` | Polygon / MultiPolygon |
| `restriction` | любая валидная geometry |

Дополнительно:

- `heat_network.diameter` обязателен;
- отсутствующий `heat_network.flow_tph` принимается за 0;
- `oks_future.flow_tph` обязателен;
- `oks_connection_point` должен иметь `oks_id`, либо собственный ID используется при наличии локального flow;
- `restriction_type` должен присутствовать в `RestrictionRules`.

---

# 6. Система координат

Класс:

```text
CrsTransformer
```

Преобразование:

```text
EPSG:4326 / WGS84
→ UTM zone 37N / EPSG:32637
```

Во внутренней модели все:

- расстояния;
- длины;
- buffer;
- clearance;
- chainage;
- routing;
- depth ramps

измеряются в метрах.

---

# 7. Формирование InputSnapshot

Класс:

```text
SnapshotLoader
```

Snapshot содержит:

- features;
- terminals;
- source;
- existing network;
- chambers;
- restrictions.

Расход terminal:

```text
flow(terminal) =
    flow_tph соответствующего oks_future,
    если найден;
    иначе flow_tph точки;
    иначе 0
```

Для spatial restrictions формируется рабочий envelope вокруг terminals с padding 5000 м.

Существующая сеть загружается полностью.

---

# 8. Восстановление существующей топологии

Класс:

```text
ExistingTopologyResolver
```

Если upstream-ссылки отсутствуют, они восстанавливаются по геометрии.

Концы труб считаются одной вершиной, если:

```text
distance < 2 m
```

Из существующих труб строится неориентированный граф.

От source рассчитывается расстояние по длинам труб.

Для трубы с отсутствующим upstream выбирается конец, находящийся ближе к source.

Upstream ID назначается как:

1. `source`, если вершина совпадает с source;
2. ID камеры, если в вершине есть камера;
3. ID parent edge иначе.

Несвязная с source компонента является ошибкой.

Если у камеры отсутствовал diameter:

```text
DN(chamber) = max DN примыкающих труб
```

---

# 9. Нормативная таблица DN

Источник:

```text
RuleBook.DN
```

| DN | capacity, т/ч | maxLength, м | newCost, руб/м | pairWidth, м | pairHeight, м |
|---:|---:|---:|---:|---:|---:|
| 50 | 3.5 | 181 | 74 023 | 0.400 | 0.125 |
| 65 | 8.3 | 245 | 78 631 | 0.430 | 0.140 |
| 80 | 13.2 | 327 | 83 530 | 0.470 | 0.160 |
| 100 | 22.3 | 419 | 89 748 | 0.510 | 0.180 |
| 125 | 40.2 | 554 | 97 275 | 0.600 | 0.225 |
| 150 | 65.1 | 696 | 105 507 | 0.650 | 0.250 |
| 200 | 152.3 | 1042 | 120 275 | 0.880 | 0.315 |
| 250 | 274.9 | 1379 | 135 323 | 1.050 | 0.400 |
| 300 | 437.4 | 1718 | 150 022 | 1.150 | 0.450 |
| 400 | 943.1 | 2477 | 190 299 | 1.370 | 0.560 |
| 500 | 1663.4 | 3245 | 224 137 | 1.670 | 0.710 |
| 600 | 2627.7 | 4037 | 264 790 | 1.850 | 0.800 |
| 700 | 3735.1 | 4775 | 324 298 | 2.050 | 0.900 |
| 800 | 5296.8 | 5644 | 325 996 | 2.250 | 1.000 |
| 900 | 7165.0 | 6518 | 327 693 | 2.450 | 1.100 |
| 1000 | 9391.8 | 7419 | 418 777 | 2.650 | 1.200 |
| 1200 | 15012.8 | 9288 | 428 074 | 3.100 | 1.425 |
| 1400 | 22501.9 | 11276 | 683 417 | 3.450 | 1.600 |

Минимальный DN:

```text
minForFlow(q) =
    первый DN, для которого capacity(DN) >= q
```

---

# 10. Стоимость камер

```text
DN <= 200       → 3 000 000
DN <= 500       → 5 000 000
DN <= 1000      → 8 000 000
DN > 1000       → 12 000 000
```

Для новой камеры используется максимальный DN примыкающих новых труб.

---

# 11. Штраф неподключения

```text
P(flow) = 100 000 000 + 500 000 × flow
```

Большой фиксированный штраф делает полное подключение приоритетным, если допустимая трасса существует.

---

# 12. Итоговый score

```text
S = 0.7 × C / 25 000 000
  + 0.3 × L / 100
```

Где:

- `C` — calculated cost;
- `L` — суммарная длина сети.

Меньшее значение лучше.

---

# 13. Генерация точек подключения

Класс:

```text
TieCandidateGenerator
```

Для terminal создаются кандидаты:

1. существующие камеры;
2. projection terminal на каждую existing pipe;
3. конец существующей трубы;
4. регулярные точки вдоль существующих труб.

Шаг:

```text
spacing = max(10 m, gridStep × 8)
```

При `gridStep=3`:

```text
spacing = 24 m
```

---

# 14. Правило камеры в радиусе 10 м

Кандидат на трубе canonicalize-ится.

Если в пределах 10 м существует доступная камера и её degree позволяет ещё одно присоединение, pipe candidate заменяется этой камерой.

Это предотвращает создание новой камеры рядом с существующей пригодной.

---

# 15. Сортировка tie candidates

Эвристическая оценка включает:

```text
candidateScore =
    distance
  + capacityRisk
  + chamberBonus
  + deterministicDiversity
```

`deterministicDiversity` зависит от ID и seed и используется для воспроизводимого разнообразия стартов.

---

# 16. Пространственные ограничения

`RestrictionRules` задаёт:

| type | crossing | clearance | min angle | factor | vertical gap | extra |
|---|---|---:|---:|---:|---:|---:|
| park | нет | 1.0 | — | 1.0 | — | — |
| social_area | нет | 1.0 | — | 1.0 | — | — |
| prohibited_site | нет | 1.0 | — | 1.0 | — | — |
| water | нет | 1.0 | — | 1.0 | — | — |
| oks | нет | 1.0 | — | 1.0 | — | — |
| road | да | 1.5 | 45° | 1.60 | 0 | 3 |
| tram_tracks | да | 1.5 | 45° | 1.75 | 0 | 3 |
| railway | нет | 1.5 | — | 1.0 | — | — |
| gas_pipeline | да | 2.0 | 0° | 1.25 | 0.2 | 2 |
| power_cable | да | 2.0 | 0° | 1.15 | 0.5 | 2 |
| heat_network | да | 1.0 | 0° | 1.05 | 0.5 | 2 |

---

# 17. Clearance здания

Для `oks_existing`:

```text
DN < 500        → 5 m
500 <= DN <=800 → 7 m
DN > 800        → 9 m
```

Дополнительно учитывается половина ширины пары труб.

---

# 18. RouteConstraintEngine

Основная задача класса — ответить:

```text
можно ли провести данный line segment
с данным DN
в данном режиме
с учётом всех ограничений?
```

Для ускорения используются:

- `STRtree`;
- `PreparedGeometry`;
- `IndexedFacetDistance`;
- cache forbidden buffers по DN;
- cache статических segment assessments.

---

# 19. Special crossing

Если пересечение разрешено:

1. проверяется факт intersection;
2. запрещается линейный overlap;
3. проверяется минимальный угол;
4. назначается special cost factor;
5. в DEPTH проверяется вертикальный clearance.

Road и tram требуют:

```text
cross angle >= 45°
```

---

# 20. Существующая тепловая сеть

Новая труба может:

- начинаться в tie point;
- пересекать существующую сеть;
- не может идти вдоль неё с overlap.

Вне разрешённой точки подключения clearance:

```text
1.0
+ halfWidth(new DN)
+ halfWidth(existing DN)
```

---

# 21. Новая сеть против новой сети

Каждый route attempt получает список `occupied`.

Candidate segment отклоняется, если пересекает уже принятую новую линию вне разрешённой общей точки.

Overlap длиной более 0.05 м запрещён.

---

# 22. Вход в здание

Классы:

```text
BuildingEntry
RouteConstraintEngine
SparseVisibilityRouter
```

Обычный запрет транзита через здание имеет ровно одно исключение: финальный прямой ввод в собственный terminal.

---

# 23. Внешний фасад

Используются только exterior rings.

Interior holes и внутренние дворы не рассматриваются как допустимый внешний фасад.

---

# 24. Ближайшая стена

Для terminal вычисляется:

```text
dmin = distance(terminal, exterior boundary)
```

Ближайшие допустимые точки рассматриваются первыми. При недоступном подходе алгоритм переходит к следующему фасаду; на подробном контуре рабочие точки отбираются по расстоянию и направлениям. Все выбранные вводы проверяются по исходной геометрии.

---

# 25. Approach port

Точка подхода выносится наружу:

```text
setback =
    buildingClearance(DN)
  + halfWidth(DN)
  + 0.20 m
```

Если terminal лежит на фасаде, проверяются обе нормали и остаются только точки снаружи polygon.

---

# 26. Проверка building lead

Разрешённый ввод обязан:

1. иметь terminal на одном конце;
2. пересекать только собственное здание;
3. выйти через exterior boundary;
4. быть прямым внутри здания, без повторного входа и прохода через двор;
5. вывести следующий узел за building clearance.

Другой фасад разрешён после неудачного поиска через ближайшие подходы. Вход под углом 90° не обязателен.

---

# 27. Escape nodes

Для сложных зданий добавляются дополнительные точки выхода из buffered envelope.

Проверяются направления:

```text
-90°, -45°, 0°, 45°, 90°
```

относительно направления terminal → port.

---

# 28. SparseVisibilityRouter

Router не строит raster.

Вместо этого он:

1. строит DN-aware buffers препятствий;
2. извлекает характерные boundary points;
3. строит sparse visibility graph;
4. добавляет start/goal;
5. добавляет building approach/escape points;
6. выполняет shortest-path search;
7. упрощает путь;
8. проверяет 3D feasibility.

---

# 29. Ограничение размера visibility graph

```text
MAX_CANDIDATES = 1600
```

Если точек больше, используется radial sampling вокруг центра множества.

Так сохраняется покрытие всех направлений, включая длинные barriers.

---

# 30. Sparse visibility edges

Для каждого node рассматриваются ближайшие:

```text
FAST_NEAREST_CANDIDATES = 64
```

Пространство делится на:

```text
VISIBILITY_SECTORS = 16
```

На сектор:

```text
ATTEMPTS_PER_SECTOR = 6
NEIGHBORS_PER_SECTOR = 2
```

Это резко уменьшает число exact geometry checks относительно полного visibility graph.

---

# 31. Кэширование graph

Static graph строится один раз на:

```text
(DN, RunMode)
```

и переиспользуется между route attempts одного scenario.

Также кешируются baseline routes без новых линий.

Если baseline не пересекает текущий `occupied`, он возвращается без нового полного поиска.

---

# 32. Состояние shortest path

Состояние:

```text
(node, previousNode)
```

Previous node необходим для контроля поворота.

---

# 33. Ограничение угла

Если scalar product incoming/outgoing отрицателен:

```text
incoming · outgoing < 0
```

то поворот больше 90°, переход запрещается.

---

# 34. Bend penalty

Значимый поворот получает heuristic penalty:

```text
8 m equivalent
```

Это routing penalty, а не отдельная денежная стоимость.

---

# 35. Priority search

Приоритет:

```text
priority =
    accumulated path cost
  + Euclidean distance to target
```

Поиск работает как A*-подобный алгоритм на sparse graph.

---

# 36. Simplification

После найденного пути итеративно проверяется:

```text
A - B - C
```

Если `A-C`:

- legal;
- не создаёт запрещённый поворот;
- не нарушает building entry,

узел `B` удаляется.

После simplification в 3D повторно проверяется возможность depth profile.

---

# 37. SharedNetworkBuilder

Главная конструктивная стадия строит лес, а не независимые линии.

Новый ОКС может подключаться:

1. к existing network;
2. к existing chamber;
3. к новой chamber;
4. к уже построенному новому edge.

Последний случай позволяет создавать общие trunk-участки.

---

# 38. Начальное состояние plan

```text
edges = empty
roots = empty
unconnected = all terminals
```

Сразу рассчитывается score со штрафами за все неподключённые ОКС.

---

# 39. Frontier tie candidates

Изначально для terminal используется до:

```text
24 candidates
```

Если маршрута нет:

```text
24 → 96 → полный список
```

Расширение выполняется только после неудачи дешёвого frontier.

---

# 40. Attachments к уже построенной сети

Для очередного terminal рассматриваются:

### Existing tie

Candidate из существующей сети.

### Internal plan node

Любой non-terminal node с degree <4.

### Existing plan edge

Projection terminal на edge.

Если подключение идёт в середину edge, создаётся новая `CHAMBER`, а исходный edge делится.

---

# 41. Проверка каждого attachment

Для candidate:

1. определяется DN по flow;
2. строится route;
3. создаётся копия plan;
4. terminal присоединяется;
5. выполняется `PlanEvaluator`;
6. выполняется `EngineeringValidator`.

Недопустимый candidate сразу отбрасывается.

---

# 42. Выбор следующего подключения

Сравнивается полный change score с учётом снятия штрафа за terminal.

Концептуально:

```text
increment =
    score(candidate)
  - score(current)
  + removedPenaltyNormalized
```

Выбирается candidate с минимальным increment.

---

# 43. Flow aggregation

Классы:

```text
FlowDiameterCalculator
PipeSizing
```

Для terminal:

```text
flow(node) = q(oks)
```

Для внутреннего node:

```text
flow(node) = Σ flow(child edge)
```

Для edge:

```text
flow(edge) = flow(edge.child)
```

---

# 44. DN по расходу

Первичный DN:

```text
DN(edge) = minForFlow(flow(edge))
```

---

# 45. Монотонность DN

По пути от terminal к root DN не должен уменьшаться.

Если upstream edge имеет меньший DN, он повышается до downstream requirement.

---

# 46. Ограничение длины одинакового DN

Для непрерывной части с одинаковым расчётным расходом:

```text
runLength = Σ length
requiredFlow = max flow
```

Подбирается первый DN, для которого одновременно:

```text
capacity >= requiredFlow
maxLength >= runLength
DN >= currentDN
```

Процесс повторяется до convergence.

---

# 47. SegmentCostModel

Стоимость участка:

```text
pieceCost =
    horizontalLength
  × newCost(DN)
  × specialFactor
  × averageDepthFactor
```

---

# 48. Special factors

```text
road          1.60
tram_tracks   1.75
gas_pipeline  1.25
power_cable   1.15
heat_network  1.05
```

Если один piece попадает под несколько факторов, применяется максимальный.

---

# 49. Стоимость подключения и камер

Подключение нового участка к существующей камере:

```text
5 000 000 руб.
```

Подключение непосредственно к existing pipe реализуется как новая камера в выбранной точке и учитывается через chamber construction cost.

---

# 50. Calculated cost

В текущем evaluator:

```text
calculatedCost =
    constructionCost
  + chamberCost
  + tieInCost
  + unconnectedPenalty
```

Поля реконструкции существуют в модели и summary, но принимаются равными нулю.

---

# 51. JunctionRefiner

Новые камеры локально двигаются по шагам:

```text
24, 12, 6, 3, 1 m
```

Для каждого шага до трёх passes.

На каждом уровне проверяются восемь направлений через 45°.

Изменение сохраняется только если:

```text
newScore < oldScore
AND validation passed
```

---

# 52. PlanLocalSearch

Для каждого edge строится прямой segment между parent и child.

Если он короче:

1. geometry временно заменяется;
2. пересчитывается plan;
3. выполняется validator;
4. изменение сохраняется только при меньшем `S`.

---

# 53. JointPlanRefiner

Это совместная дискретная оптимизация положений узлов.

Шаги:

```text
12 → 3 → 1 m
```

На каждом шаге до трёх passes.

---

# 54. Candidate positions

## CHAMBER

- текущая точка;
- 8 точек на окружности радиуса `step`.

## TIE_IN на existing pipe

- текущая точка;
- chainage - step;
- chainage + step.

Candidate отбрасывается, если нарушает правило существующей камеры в 10 м.

## TERMINAL

Положение фиксировано.

---

# 55. Edge cost matrices

Для каждой пары соседних nodes строится:

```text
cost[parentCandidate][childCandidate]
```

Проверяются:

1. исходная polyline с новыми endpoints;
2. direct segment.

Если geometry illegal:

```text
cost = +∞
```

Иначе локальная оценка использует construction cost и длину через официальную формулу score.

---

# 56. JointChamberMilp

Логическая модель:

```text
x[node,candidate]
y[parent,left,child,right]
```

`x` выбирает позицию node.

`y` соответствует выбранной паре positions на edge.

Но текущая топология — rooted tree, поэтому вместо внешнего MILP solver используется специальная точная dynamic programming схема.

---

# 57. Tree dynamic programming

Для каждого node и каждого candidate рассчитываются top-k subtree solutions.

Leaf:

```text
solution(node,candidate) = 0
```

Internal node объединяет:

```text
edgeCost(parentCandidate, childCandidate)
+
child subtree solution
```

`+∞` пары игнорируются.

---

# 58. Top-k merge

Для нескольких child branches требуется найти k наименьших сумм по одному решению из каждой ветви.

Используется priority queue по массиву indices.

Начальное состояние:

```text
[0,0,...,0]
```

Затем поочерёдно увеличивается индекс одной branch.

Limit:

```text
8 assignments
```

---

# 59. Что является точным

`JointChamberMilp` точно решает дискретную tree-задачу для:

- фиксированной topology;
- фиксированного candidate set;
- фиксированных edge matrices.

Это не является доказательством глобального минимума непрерывной задачи трассировки.

---

# 60. Full-plan acceptance

Каждый assignment материализуется в полный `NetworkPlan`.

После этого заново:

- обновляется tie chainage;
- рассчитываются flow;
- рассчитываются DN;
- рассчитывается cost;
- рассчитывается score;
- выполняется validator.

Assignment принимается только при строгом улучшении полного `S`.

---

# 61. Reconnect leaves

После появления новых trunk segments ранее подключённый terminal может получить более выгодную точку присоединения.

Алгоритм:

1. terminal временно отсоединяется;
2. удаляется leaf edge;
3. пустые ancestor nodes удаляются;
4. terminal становится `unconnected`;
5. заново генерируются attachments;
6. проверяются новые routes;
7. принимается только вариант с меньшим полным `S`.

---

# 62. Multi-start search

В `UniversalOptimizer`:

```text
attempts = competition.optimizer.maxStarts (default: 3)
```

По умолчанию выполняются три старта и локальное упрощение. По умолчанию включены ограниченный joint refinement (12, 3, 1 м; один проход; 8 назначений) и один reconnect. `competition.optimizer.refine=false` отключает их для самого быстрого расчёта. Автоматического увеличения числа стартов ради трёх вариантов нет.

---

# 63. Deterministic diversification

Первый seed использует salt 0.

Остальные зависят от:

```text
seed index
+
randomSeed mod 10000
```

Default:

```text
randomSeed = 20260922
```

Таким образом поиск воспроизводим.

---

# 64. Цикл одного seed

```text
plan = SharedNetworkBuilder.build

JunctionRefiner.improve(plan)

plan = JointPlanRefiner.improve(plan)

archive valid copy

repeat:
    reconnectLeaves
    PlanLocalSearch
    JointPlanRefiner
    archive valid copy

    if score did not improve:
        break
```

Rounds:

```text
2D    → до 2
DEPTH → до 3
```

---

# 65. 3D профиль: базовые условия

```text
normal depth = 3.0 m
minimum depth = 0.7 m
maximum slope = 0.10
```

В каждом plan node глубина должна вернуться к 3 м.

---

# 66. Depth multiplier

```text
depthFactor(d) =
    1.0,                  d <= 3
    1 + 0.10 × (d - 3),   d > 3
```

---

# 67. Пересечение газопровода

Модель:

```text
top = 2.8 m
height = 0.4 m
gap = 0.2 m
```

---

# 68. Пересечение силового кабеля

```text
top = 2.7 m
height = 0.2 m
gap = 0.5 m
```

---

# 69. Пересечение существующей тепловой сети

```text
top = 3.0 m
height = pairHeight(existing DN)
gap = 0.5 m
```

---

# 70. Проход сверху

Для новой трубы:

```text
aboveDepth =
    obstacleTop
  - gap
  - pairHeight(new DN)
```

Он допустим, если:

1. `aboveDepth >= 0.7`;
2. до концов edge хватает длины для plateau и ramps.

---

# 71. Проход снизу

Если проход сверху невозможен:

```text
belowDepth =
    obstacleTop
  + obstacleHeight
  + gap
```

---

# 72. Plateau

Для utility crossing:

```text
2 m до crossing
+
2 m после crossing
```

Итого 4 м постоянной глубины.

---

# 73. Ramp

Уклон:

```text
|Δdepth| / horizontalLength <= 0.10
```

Минимальная длина перехода:

```text
rampLength = |targetDepth - 3| / 0.10
```

---

# 74. Пересекающиеся depth constraints

`SegmentCostModel.reconcileDepths()` согласует соседние plateaus и ramps, чтобы профиль оставался непрерывным.

---

# 75. Breakpoints depth profile

Профиль разбивается в:

- начале;
- конце;
- границах plateau;
- границах ramps;
- точках пересечения depth envelopes.

Эти точки могут превращаться в `technical_node` при export.

---

# 76. DepthProfileValidator

Независимо проверяется:

```text
start depth == 3
end depth == 3
depth >= 0.7
continuity
slope <= 0.10
```

Для road:

```text
depth >= 1.0
```

Для tram:

```text
depth >= 1.2
```

Для utility crossing проверяется above/below clearance и постоянная глубина на plateau ±2 м.

---

# 77. EngineeringValidator: topology

Проверяется:

- degree node ≤4;
- terminal — leaf;
- connected terminal имеет parent;
- нет duplicate terminal;
- tie root не имеет parent;
- нет orphan node;
- degree existing chamber ≤4;
- degree pipe-tie chamber ≤4;
- соблюдается 10 m chamber rule;
- connected + unconnected совпадает с input terminals;
- root без parent;
- компоненты acyclic.

---

# 78. EngineeringValidator: flow/DN

Validator заново вызывает:

```text
PipeSizing.requiredDiameters
```

и требует:

```text
stored DN == independently required DN
```

---

# 79. EngineeringValidator: geometry

Для каждого edge:

- первая coordinate совпадает с parent;
- последняя coordinate совпадает с child;
- special crossing — прямой section;
- каждый segment повторно проходит `RouteConstraintEngine`;
- поворот не более 90°.

Для пары новых edges пересечение допустимо только в общей node.

---

# 80. EngineeringValidator: score

Повторно вычисляются:

```text
cost
length
score
```

и сравниваются с сохранёнными значениями.

---

# 81. Diversity

Класс:

```text
VariantDiversity
```

Сравниваются три аспекта.

## Tie-ins

Set:

```text
existingObjectType : existingObjectId
```

## Topology

Сравниваются:

- terminals по root components;
- grouping leaves у branch nodes.

## Corridor geometry

Edges дискретизируются в клетки:

```text
12 m
```

и сравниваются Jaccard distance.

---

# 82. Material difference

Plans считаются существенно различными, если выполняется хотя бы одно:

```text
tie-in distance >= 0.15
OR topology distance >= 0.15
OR corridor distance >= 0.25
```

---

# 83. Coverage priority

Перед финальным отбором остаются только candidates с минимальным количеством `unconnected`.

Если существует fully connected plan, partial plans не входят в финальный portfolio.

---

# 84. MilpPortfolioSelector

Выбирается максимум 3 попарно совместимых по diversity plan.

Перебираются:

- singleton;
- pairs;
- triples.

Критерии:

1. максимальный размер portfolio;
2. минимальный `S` лучшего варианта;
3. минимальная сумма `S`.

ID после сортировки:

```text
2d_1
2d_2
2d_3

3d_1
3d_2
3d_3
```

---

# 85. GeoJSON export

Класс:

```text
GeoJsonResultWriter
```

Текущие object types:

```text
heat_network
heat_chamber
technical_node
variant_summary
```

Один logical plan edge может быть разбит на несколько output features из-за:

- special laying sections;
- depth ramps;
- plateaus;
- profile breakpoints.

---

# 86. 2D export

```text
[longitude, latitude]
```

```text
depth_start = null
depth_end = null
```

---

# 87. 3D export

```text
[longitude, latitude, -depth]
```

Z интерполируется вдоль каждого output piece.

---

# 88. Variant summary

Содержит:

- construction cost;
- chamber construction cost;
- tie-in cost;
- reconstruction cost;
- chamber reconstruction cost;
- unconnected penalty;
- calculated cost;
- new network length;
- reconstruction length;
- total length;
- score;
- unconnected IDs.

Geometry summary равна `null`.

---

# 89. OutputGeoJsonValidator

После записи файл проверяется ещё раз независимо от in-memory plan.

Проверяется:

- root object;
- `FeatureCollection`;
- `features`;
- допустимые object types;
- mandatory fields;
- coordinate dimensions;
- unique IDs;
- totals;
- score.

---

# 90. Проверка координат

Position:

```text
array
2 или 3 numeric values
all finite
```

Для `heat_network`:

```text
2D → exactly 2 values
3D → exactly 3 values
```

Для 3D:

```text
Z_start == -depth_start
Z_end == -depth_end
```

---

# 91. Проверка totals

Validator суммирует exported feature cost и length.

После этого сверяет:

```text
sum(feature costs) + penalty == calculated_cost
sum(feature lengths) == length
officialScore(cost,length) == score
```

---

# 92. End-to-end pseudocode

```text
function optimizeScenario(input, mode):

    scenario = streamingIngest(input)

    snapshot = loadSnapshot(scenario)
    restoreExistingTopology(snapshot)

    candidates = []

    attempts = initialAttempts()

    for seed in attempts:

        plan = buildSharedForest(snapshot, mode, seed)

        refineJunctions(plan)
        plan = jointlyRefine(plan)
        archiveIfValid(plan)

        rounds = mode == DEPTH ? 3 : 2

        for round in rounds:

            before = plan.score

            plan = reconnectLeaves(plan)
            simplifyEdges(plan)
            plan = jointlyRefine(plan)

            archiveIfValid(plan)

            if plan.score >= before:
                break

        evaluate(plan)
        validate(plan)

        if valid:
            candidates.add(plan)

        if finalAttempt
           and fewer than 3 diverse candidates
           and attempts < 8:
            extend attempts

    if candidates empty:
        fail

    bestCoverage = minimum unconnected count

    candidates =
        only candidates with bestCoverage

    selected =
        exact portfolio selection,
        max size 3

    sort selected by score
    assign variant IDs

    export selected
    validate exported file

    return selected
```

---

# 93. Shared-network pseudocode

```text
function buildSharedForest(snapshot, mode, seed):

    plan = empty
    plan.unconnected = all terminals

    build root tie candidate lists

    while unconnected not empty:

        best = none

        order pending terminals by
            proximity to existing/new network
            × deterministic seed factor

        for terminal in pending:

            attachments =
                existing ties
                + available plan nodes
                + points on plan edges

            for attachment:

                route = sparseVisibilityRoute(
                    attachment,
                    terminal,
                    requiredDN,
                    occupied
                )

                if route absent:
                    continue

                candidate = attach(copy(plan), route)

                evaluate(candidate)
                validate(candidate)

                if invalid:
                    continue

                keep best score increment

            if best found:
                break

        if no best:
            expand tie candidate frontier

            if already exhaustive:
                break

            continue

        plan = best

    return validated plan
```

---

# 94. Routing pseudocode

```text
function route(start, goal, DN, mode, occupied):

    if cached baseline exists
       and not blocked by occupied:
        return cached baseline

    if direct segment legal
       and depth profile feasible:
        return direct

    ports = nearest building facade approaches

    template = cached visibility graph(DN,mode)

    graph = copy(template)

    add:
        start
        goal
        ports
        building escape nodes

    connect legal endpoint visibility edges

    path = shortestPath(
        state = (node, previousNode)
    )

    if absent:
        return none

    simplify(path)

    if DEPTH and simplified profile infeasible:
        try original path

    return feasible path
```

---

# 95. Joint refinement pseudocode

```text
function jointlyRefine(plan):

    for step in [12,3,1]:

        one pass:

            positions[node] =
                legal candidate positions

            for each tree edge:
                build pairwise cost matrix

            assignments =
                exactTreeTopK(limit=8)

            best = current plan

            for assignment:

                candidate =
                    materialize full plan

                recalculate:
                    flow
                    DN
                    cost
                    score

                validate

                if valid and score improves:
                    best = candidate

            if best unchanged:
                break

            plan = best

    return plan
```

---

# 96. Сложность и оптимизации

## Ingest

Для N features parsing линейный:

```text
O(N)
```

## Existing topology

Текущая реализация выбора ближайшей pending vertex использует линейный поиск, поэтому worst-case около:

```text
O(V² + E)
```

## Visibility graph

Полный graph был бы `O(V²)` exact checks.

Текущий sparse подход ограничивает число проверяемых соседей фиксированными frontier/sector constants.

## Joint refinement

Полный перебор candidate positions имел бы экспоненциальный размер.

Tree DP факторизует задачу по branches и сохраняет только top-k partial solutions.

---

# 97. Основные кэши

## RouteConstraintEngine

- prepared restrictions;
- indexed facet distance;
- forbidden buffers per DN;
- static segment assessments;
- building lead checks.

## SparseVisibilityRouter

- restriction boundaries per DN;
- graph templates per DN/mode;
- baseline routes;
- building approach points;
- escape nodes;
- entry assessments.

## PlanEvaluator

- construction costs по geometry/DN/mode.

## JointPlanRefiner

- legal route cost cache.

---

# 98. Детерминизм

Production-код не использует:

- фиксированные координаты;
- фиксированные ID конкурсного файла;
- готовый output GeoJSON;
- Python subprocess;
- внешний solver.

Все решения выводятся из загруженного `InputSnapshot`.

Default seed:

```text
20260922
```

---

# 99. Generalization regression

`GeneralizationSmokeTest` формирует synthetic scenarios с:

- другими ID;
- случайными UUID;
- сдвинутыми координатами.

На каждом запускаются 2D и 3D.

Это проверяет отсутствие зависимости от конкретного конкурсного набора.

---

# 100. Ограничения алгоритма

Алгоритм не доказывает глобальный минимум непрерывной задачи.

Он комбинирует:

- multi-start constructive search;
- exact geometry routing;
- local search;
- exact discrete tree assignment;
- topology reconnection;
- diversity portfolio selection.

Точным является только `JointChamberMilp` для заданного discrete candidate set и фиксированной tree topology.

Если существует меньше трёх действительно различных инженерно допустимых решений, система не должна искусственно генерировать их.

---

# 101. Карта стадий на Java-классы

| Стадия | Класс |
|---|---|
| HTTP upload | `ScenarioController` |
| ingest | `GeoJsonStreamingIngestor` |
| CRS | `CrsTransformer` |
| snapshot | `SnapshotLoader` |
| topology recovery | `ExistingTopologyResolver` |
| tie candidates | `TieCandidateGenerator` |
| shared construction | `SharedNetworkBuilder` |
| routing | `SparseVisibilityRouter` |
| building entry | `BuildingEntry` |
| constraints | `RouteConstraintEngine` |
| flow | `FlowDiameterCalculator` |
| DN | `PipeSizing` |
| segment cost/depth | `SegmentCostModel` |
| plan score | `PlanEvaluator` |
| chamber refinement | `JunctionRefiner` |
| local search | `PlanLocalSearch` |
| joint refinement | `JointPlanRefiner` |
| exact tree solver | `JointChamberMilp` |
| diversity | `VariantDiversity` |
| portfolio | `MilpPortfolioSelector` |
| plan validation | `EngineeringValidator` |
| depth validation | `DepthProfileValidator` |
| export | `GeoJsonResultWriter` |
| output validation | `OutputGeoJsonValidator` |
| run orchestration | `OptimizationService` |

---

# 103. Ключевые инварианты

## Топология

```text
acyclic
degree <= 4
terminal is leaf
root is tie-in
no orphan internal node
connected ∪ unconnected = all input OKS
```

## Геометрия

```text
no forbidden transit
no building transit except own terminal lead
no new-new crossing
no illegal overlap
turn <= 90°
required clearances preserved
special crossings valid
```

## Flow/DN

```text
flow(edge) = downstream demand
DN(edge) = required PipeSizing value
DN does not decrease toward root
constant-flow chain has uniform DN satisfying total length
```

## 3D

```text
node depth = 3 m
depth >= 0.7 m
slope <= 0.10
vertical clearance valid
plateau = 4 m
profile continuous
```

## Экономика

```text
summary cost = sum exported costs + penalties
summary length = sum exported network lengths
summary score = official formula
```

---

# 104. Условие успешного завершения run

```text
optimizer produced plans
→ selected plans are engineering-valid
→ GeoJSON written
→ OutputGeoJsonValidator returned no errors
→ status = DONE
```

При exception:

```text
status = ERROR
phase = ERROR
error = exception text
```

---

# 105. Команды проверки

Полный end-to-end:

```bash
./RUN_TASK.sh 'task/Датасет скорректированный.geojson' results
```

Все тесты:

```bash
mvn test
```

Статическая проверка:

```bash
./scripts/verify_project.sh
```

Quality test сохраняет свои результаты в:

```text
target/optimized-results/
```



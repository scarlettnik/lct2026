# TeploKontur

Java 11 / Spring Boot 2.6.3 / PostgreSQL. Сервис строит варианты подключения
ОКС к тепловой сети в 2D и с учётом глубины. Вход и выход — GeoJSON WGS84;
расстояния рассчитываются в EPSG:32637.

## Получить результат одной командой

Нужны Docker с Compose, Bash, `jq` и curl ≥ 7.76. Команды выполняются из корня проекта.
Docker должен иметь доступ к интернету при первой сборке и достаточно памяти
для JVM с лимитом 2 ГиБ и PostgreSQL.

```bash
./RUN_TASK.sh 'task/Датасет скорректированный.geojson' results
```

Скрипт собирает актуальный код, запускает БД и backend, загружает файл,
последовательно рассчитывает 2D и 3D и сохраняет:
#!/usr/bin/env bash
# Upload → 2D → 3D → validated GeoJSON. Run from the project root.
set -euo pipefail
DATASET="${1:-task/Датасет скорректированный.geojson}"
OUTPUT_DIR="${2:-results-$(date +%Y%m%d-%H%M%S)}"
BASE_URL="${BASE_URL:-http://localhost:${APP_PORT:-8080}/api/v1}"
POLL_SECONDS="${POLL_SECONDS:-10}"
MAX_POLLS="${MAX_POLLS:-720}"
MIN_VARIANTS="${MIN_VARIANTS:-3}"
REQUIRE_CONNECTED="${REQUIRE_CONNECTED:-1}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
for command in curl jq; do
  command -v "$command" >/dev/null || fail "Не найдена команда: $command"
done
[[ -f "$DATASET" ]] || fail "Датасет не найден: $DATASET"
mkdir -p "$OUTPUT_DIR"

if [[ "${SKIP_DOCKER:-0}" != 1 ]]; then
  if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null; then COMPOSE=(docker-compose)
  else fail 'Не найден Docker Compose'; fi
  "${COMPOSE[@]}" up --build -d
fi

ready=0
for ((attempt=1; attempt<=60; attempt++)); do
  if curl --fail --silent --output /dev/null "${BASE_URL%/api/v1}/swagger-ui.html"; then ready=1; break; fi
  sleep 2
done
[[ "$ready" == 1 ]] || fail 'API недоступно. Проверьте адрес и docker compose logs app'

curl --fail-with-body --silent --show-error -F "file=@${DATASET}" "$BASE_URL/scenarios" \
  --output "$OUTPUT_DIR/scenario.json"
scenario_id="$(jq -er 'select(.status == "READY") | .scenario_id' "$OUTPUT_DIR/scenario.json")"
printf 'Сценарий: %s\n' "$scenario_id"

for mode in 2d 3d; do
  curl --fail-with-body --silent --show-error -X POST "$BASE_URL/scenarios/$scenario_id/runs?mode=$mode" \
    --output "$OUTPUT_DIR/run-$mode.json"
  run_id="$(jq -er '.run_id' "$OUTPUT_DIR/run-$mode.json")"
  status=''
  for ((attempt=1; attempt<=MAX_POLLS; attempt++)); do
    curl --fail-with-body --silent --show-error "$BASE_URL/runs/$run_id" \
      --output "$OUTPUT_DIR/status-$mode.json"
    status="$(jq -er '.status' "$OUTPUT_DIR/status-$mode.json")"
    printf '[%s] %s\n' "$mode" "$status"
    case "$status" in
      DONE) break ;;
      ERROR) cat "$OUTPUT_DIR/status-$mode.json" >&2; fail "Ошибка расчёта $mode" ;;
    esac
    sleep "$POLL_SECONDS"
  done
  [[ "$status" == DONE ]] || fail "Расчёт $mode ещё не завершён; run_id=$run_id. Увеличьте MAX_POLLS."
  curl --fail-with-body --silent --show-error "$BASE_URL/runs/$run_id/variants" \
    --output "$OUTPUT_DIR/variants-$mode.json"
  jq -e --argjson minimum "$MIN_VARIANTS" \
    'length >= $minimum and length <= 3 and all(.[]; .validation_passed == true)' \
    "$OUTPUT_DIR/variants-$mode.json" >/dev/null || fail "Недостаточно допустимых вариантов $mode"
  curl --fail-with-body --silent --show-error "$BASE_URL/runs/$run_id/export" \
    --output "$OUTPUT_DIR/result-$mode.geojson"
  if [[ "$REQUIRE_CONNECTED" == 1 ]]; then
    jq -e '[.features[].properties | select(.object_type == "variant_summary")] | length > 0 and all(.[]; (.unconnected_oks_ids | length) == 0)' \
      "$OUTPUT_DIR/result-$mode.geojson" >/dev/null || fail "Есть неподключённые точки в $mode"
  fi
  jq '[.features[].properties | select(.object_type == "variant_summary") | {variant_id, score, unconnected_oks_ids}]' \
    "$OUTPUT_DIR/result-$mode.geojson"
done
printf 'Готово: %s\n' "$OUTPUT_DIR"

- `results/scenario.json` — идентификатор загруженного сценария;
- `results/run-2d.json`, `results/run-3d.json` — идентификаторы расчётов;
- `results/status-*.json` — последние статусы;
- `results/variants-*.json` — оценки и инженерная валидация;
- `results/result-2d.geojson`, `results/result-3d.geojson` — итоговые сети.

Для каждого режима скрипт требует три валидных варианта и отсутствие
неподключённых ОКС. При нарушении проверок он завершается с ошибкой.
S выводится в терминал; **меньше — лучше**. Расчёт включает несколько порядков
подключения и оптимизацию камер, поэтому может занимать десятки минут.
По умолчанию скрипт ожидает до двух часов на режим.

Чтобы воспроизвести результат, используйте тот же файл, версию кода и параметры
из `src/main/resources/application.yml`, включая `random-seed: 20260922`.
Сценарии и расчёты получают новые ID; геометрия и оценки определяются входом
и параметрами алгоритма. Данные из каталога `results-*` не используются как
готовые решения.

Если порт 8080 занят:

```bash
APP_PORT=18080 ./RUN_TASK.sh 'task/Датасет скорректированный.geojson' results
```

Для своего датасета, где допустим частичный результат или меньше трёх вариантов:

```bash
MIN_VARIANTS=1 REQUIRE_CONNECTED=0 ./RUN_TASK.sh input.geojson results-custom
```

## Запуск сервиса отдельно

```bash
docker compose up --build -d
docker compose logs -f app
```

Swagger: <http://localhost:8080/swagger-ui.html>.
Сборка контейнера компилирует приложение; длительный расчёт датасета выполняется
при вызове API, а не во время сборки.

Если сервис уже запущен, скрипт можно выполнить без пересборки:

```bash
SKIP_DOCKER=1 BASE_URL=http://localhost:8080/api/v1 \
  ./RUN_TASK.sh 'task/Датасет скорректированный.geojson' results
```

Остановка с сохранением данных:

```bash
docker compose down
```

## Локальный запуск без контейнера приложения

Нужны JDK 11+, Maven 3.8+ и PostgreSQL. Код компилируется для Java 11.
Если локальной БД нет, запустите отдельную:

```bash
docker run -d --name teplokontur-local-db \
  -p 127.0.0.1:5432:5432 \
  -e POSTGRES_DB=teplokontur -e POSTGRES_USER=teplo -e POSTGRES_PASSWORD=teplo \
  postgres:15
```

Дождитесь готовности БД в `docker logs teplokontur-local-db`, затем:

```bash
mvn -DskipTests package
java -Xms256m -Xmx2g -jar target/teplokontur-1.0.0.jar
```

При другой БД задайте `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`. Для выгрузки результатов используйте команду
с `SKIP_DOCKER=1` из предыдущего раздела.

## API и потоковая загрузка

Поддерживаются оба способа загрузки до **3 ГиБ**:

```bash
# Совместимый multipart-запрос: ровно одна часть file.
curl --fail-with-body -F 'file=@task/Датасет скорректированный.geojson' \
  http://localhost:8080/api/v1/scenarios

# Прямой поток тела запроса, в том числе Transfer-Encoding: chunked.
curl --fail-with-body -H 'Content-Type: application/geo+json' \
  --data-binary '@task/Датасет скорректированный.geojson' \
  http://localhost:8080/api/v1/scenarios
```

Путь загрузки: HTTP → потоковый multipart-парсер при необходимости → Jackson
→ один Feature → проверка и перевод координат → БД. Копии всего файла на диске
нет. После каждой партии до 200 объектов очищается контекст JPA. Уникальность ID
и ссылки проверяются в PostgreSQL; все объекты и ID не собираются в памяти Java.
Память зависит от размера партии и отдельных геометрий, а не от размера всего файла.
При ошибке JSON, ссылок, повторном ID или обрыве соединения транзакция откатывается.

Если перед сервисом установлен reverse proxy, отключите в нём буферизацию тела
запроса и настройте его лимиты загрузки. Сервис начинает разбирать данные по мере
получения; ответ `READY` возвращается после завершения загрузки и проверки ссылок.

Дальнейшие операции (`SCENARIO_ID` и `RUN_ID` берутся из ответов API):

```bash
curl --fail-with-body -X POST \
  "http://localhost:8080/api/v1/scenarios/${SCENARIO_ID}/runs?mode=2d"
# Для глубины: mode=3d.
curl --fail-with-body "http://localhost:8080/api/v1/runs/${RUN_ID}"
# Дождитесь status=DONE; при ERROR изучите поле error.
curl --fail-with-body "http://localhost:8080/api/v1/runs/${RUN_ID}/variants"
curl --fail-with-body "http://localhost:8080/api/v1/runs/${RUN_ID}/export" -o result.geojson
```

## Правила расчёта

- Вход в здание идёт по кратчайшему прямому выходу через наружную стену;
  транзит под зданиями запрещён.
- ЖД не пересекается. Дороги и трамвайные пути допускают специальные проходы
  под объектом с ограничениями угла и глубины.
- В 3D: обычная глубина 3 м, минимум 0,7 м, уклон до 0,10; у коммуникаций —
  плато длиной 4 м. Экспорт содержит `[longitude, latitude, -depth]`.
- S учитывает новое строительство, камеры, врезки в существующие камеры
  и штраф за неподключение. Реконструкция отключена согласно принятым уточнениям.
- Отсутствующий расход существующей сети принят равным 0; недостающие связи
  восстанавливаются по геометрии. Отсутствующий DN камеры определяется по соседним трубам.
- Для произвольного входа число различных допустимых вариантов может быть меньше трёх.

Подробности: [ALGORITHM.md](ALGORITHM.md),
[техническое приложение](task/Техническое%20приложение.docx).

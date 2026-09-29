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
  calculation_started=$SECONDS
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
  printf '[%s] Расчёт завершён за %s с (включая ожидание опроса)\n' "$mode" "$((SECONDS-calculation_started))"
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
  while IFS= read -r variant_id; do
    [[ -n "$variant_id" ]] || continue
    variant_file="$OUTPUT_DIR/result-$mode-$variant_id.geojson"
    curl --fail-with-body --silent --show-error \
      "$BASE_URL/runs/$run_id/variants/$variant_id/export" --output "$variant_file.part"
    jq -e --arg variant "$variant_id" \
      '.type == "FeatureCollection" and ([.features[].properties] as $p | ($p | length) > 0 and all($p[]; .variant_id == $variant) and ([ $p[] | select(.object_type == "variant_summary") ] | length) == 1)' \
      "$variant_file.part" >/dev/null || fail "Некорректный GeoJSON для варианта $variant_id"
    mv "$variant_file.part" "$variant_file"
  done < <(jq -er '.[].variant_id' "$OUTPUT_DIR/variants-$mode.json")
done
printf 'Готово: %s\n' "$OUTPUT_DIR"

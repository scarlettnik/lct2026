# Техническое приложение: подробное описание алгоритма TeploKontur

> Актуальные настройки: три старта, ограниченное улучшение и независимая проверка самопересечений и вводов по исходным полигонам. Стоимость врезки в существующую камеру экспортируется дополнительным полем `heat_network.tie_in_cost` (помимо строительного `cost`).

## 1. Назначение

Документ описывает фактический алгоритм Java-проекта TeploKontur: от загрузки GeoJSON до выбора и экспорта финальных вариантов.

Главный orchestration-класс:

```text
ru.lct.teplokontur.optimization.UniversalOptimizer
```

Основной маршрутизатор:

```text
ru.lct.teplokontur.routing.SparseVisibilityRouter
```

---

# 2. Постановка задачи

Дан GeoJSON-сценарий, содержащий источник, существующие трубы и камеры, перспективные ОКС, точки их подключения, существующие здания и ограничения территории.

Необходимо построить до трёх допустимых вариантов новой сети и определить для каждого:

1. точки присоединения к существующей сети;
2. топологию и общие trunk-участки;
3. новые камеры;
4. геометрию труб;
5. расходы и DN;
6. 3D-профиль, если выбран depth mode;
7. стоимость;
8. длину;
9. показатель `S`.

---

# 3. Полный pipeline

```text
HTTP upload
→ streaming parse
→ input validation
→ WGS84 → UTM
→ persistence
→ snapshot
→ topology recovery
→ tie candidates
→ shared forest construction
→ sparse visibility routing
→ flow aggregation
→ DN sizing
→ cost + score
→ junction refinement
→ joint node optimization
→ leaf reconnection
→ local search
→ engineering validation
→ candidate archive
→ diversity filtering
→ portfolio selection
→ GeoJSON export
→ output validation
```

2D и 3D считаются независимыми run.

---

# 4. Потоковый ingest

Класс `GeoJsonStreamingIngestor` принимает до 3 GiB.

Алгоритм:

```text
create Scenario(INGESTING)
open Jackson JsonParser
require FeatureCollection
for each Feature:
    validate structure
    parse geometry
    validate geometry/object type
    transform WGS84 → UTM
    create FeatureEntity
    append to batch
    every 200 objects:
        saveAllAndFlush
        EntityManager.clear
require exactly one source
validate references
set Scenario READY
```

Полный файл не хранится в heap.

---

# 5. Input validation

Проверяется:

- `FeatureCollection`;
- `Feature`;
- `id`;
- `object_type`;
- geometry validity;
- geometry/object type match;
- known `restriction_type`;
- unique ID;
- exactly one source;
- valid `oks_id`;
- valid existing network references.

Допустимые geometry:

| object_type | geometry |
|---|---|
| source | Point |
| heat_chamber | Point |
| oks_connection_point | Point |
| heat_network | LineString |
| oks_future | Polygon/MultiPolygon |
| oks_existing | Polygon/MultiPolygon |
| restriction | Geometry |

---

# 6. Координаты

`CrsTransformer` переводит:

```text
EPSG:4326 → UTM zone 37N / EPSG:32637
```

Все длины, расстояния, buffers и clearances считаются в метрах.

---

# 7. InputSnapshot

`SnapshotLoader` собирает:

- terminals;
- source;
- existing network;
- chambers;
- restrictions;
- исходные features.

Расход terminal берётся из соответствующего `oks_future.flow_tph`, либо локального flow, либо 0.

Для restrictions используется рабочая область вокруг terminals с padding 5000 м; existing network загружается полностью.

---

# 8. Восстановление существующей топологии

Класс `ExistingTopologyResolver`.

Концы existing pipes объединяются в одну вершину при расстоянии меньше 2 м.

Из source вычисляются расстояния по существующей сети. Для трубы без upstream upstream определяется по концу, расположенному ближе к source.

Приоритет upstream ID:

1. source ID;
2. chamber ID;
3. parent pipe ID.

Несвязная компонента отклоняется.

Если исходный diameter камеры отсутствовал:

```text
DN(chamber) = max DN примыкающих труб
```

---

# 9. Нормативная модель DN

`RuleBook.DN` содержит DN 50…1400, пропускные способности, максимальные длины, стоимость нового строительства, размеры пары труб.

Минимальный диаметр:

```text
minForFlow(q) = первый DN, capacity(DN) >= q
```

Если flow выше пропускной способности DN1400, решение для такого участка невозможно.

---

# 10. Стоимость камеры

```text
DN <= 200       → 3 млн
DN <= 500       → 5 млн
DN <= 1000      → 8 млн
DN > 1000       → 12 млн
```

---

# 11. Штраф неподключения

```text
P(q) = 100 000 000 + 500 000 × q
```

---

# 12. Score

```text
S = 0.7 × C / 25 000 000 + 0.3 × L / 100
```

где `C` — стоимость, `L` — длина. Меньшее `S` лучше.

---

# 13. Генерация tie candidates

Класс `TieCandidateGenerator`.

Для каждого terminal создаются:

1. существующие камеры;
2. projection на существующую трубу;
3. конец существующей трубы;
4. регулярные точки вдоль труб.

Шаг sampling:

```text
spacing = max(10, gridStep × 8)
```

При default `gridStep=3`: 24 м.

---

# 14. Правило 10 м до существующей камеры

Кандидат на existing pipe заменяется существующей камерой, если в радиусе 10 м есть камера, способная принять ещё одно подключение без превышения degree 4.

---

# 15. Ранжирование tie candidates

Используется эвристика:

```text
candidateScore = distance + capacityRisk + chamberBonus + deterministicDiversity
```

Diversity зависит от ID и seed, поэтому multi-start остаётся воспроизводимым.

---

# 16. RestrictionRules

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

# 17. Building clearance

Для `oks_existing`:

```text
DN < 500        → 5 m
500 <= DN <=800 → 7 m
DN > 800        → 9 m
```

Дополнительно учитывается половина ширины пары труб.

---

# 18. RouteConstraintEngine

Класс проверяет каждый line segment на:

- forbidden geometry;
- clearance;
- crossing angle;
- overlap;
- существующую сеть;
- already-built new network;
- building-entry exception.

Для ускорения применяются `STRtree`, `PreparedGeometry`, `IndexedFacetDistance`, caches буферов и segment assessments.

---

# 19. Special crossings

Если crossing разрешён, проверяется реальное пересечение, отсутствие overlap и минимальный угол.

Road и tram требуют минимум 45°.

Special factor влияет на стоимость.

---

# 20. Существующая тепловая сеть

Новая труба может начинаться в tie point и пересекать existing heat network, но не может идти вдоль неё.

Required clearance вне tie point:

```text
1.0 + halfWidth(new DN) + halfWidth(existing DN)
```

---

# 21. Запрет new-new crossing

`SparseVisibilityRouter` получает список `occupied` уже построенных новых линий.

Пересечение вне разрешённой общей точки отклоняется. Overlap более 0.05 м запрещён.

---

# 22. Ввод в здание

Используются `BuildingEntry`, `RouteConstraintEngine`, `SparseVisibilityRouter`.

Здание является forbidden geometry, кроме единственного финального прямого lead к собственному terminal.

---

# 23. Ближайший фасад

Используются только exterior rings. Внутренние дворы не являются допустимым фасадом.

Вычисляется:

```text
dmin = distance(terminal, exterior boundary)
```

Ближайшие доступные подходы рассматриваются первыми. Если маршрут не найден, выбираются следующие внешние фасады; исходные полигоны остаются обязательными для итоговой проверки.

---

# 24. Approach port

Setback наружу:

```text
buildingClearance(DN) + halfWidth(DN) + 0.20 m
```

Если terminal лежит на фасаде, проверяются обе нормали и остаются только внешние точки.

---

# 25. Проверка building lead

Lead допустим, только если:

1. terminal на одном конце;
2. пересекается только собственное здание;
3. выход расположен на exterior boundary;
4. участок внутри здания прямой и не проходит транзитом через здание;
5. внешний конец находится за required clearance.

При недоступном ближайшем подходе разрешён другой внешний фасад. Перпендикулярность ввода не требуется.

---

# 26. Escape nodes

Для сложной геометрии добавляются escape points по направлениям:

```text
-90°, -45°, 0°, 45°, 90°
```

Это позволяет обойти крылья здания после выхода через выбранный допустимый фасад.

---

# 27. SparseVisibilityRouter

Алгоритм не растрирует всю область.

Он строит DN-aware visibility graph по точной геометрии JTS.

Основные стадии:

1. buffered restriction boundaries;
2. candidate vertices;
3. sparse visibility edges;
4. splice start/goal;
5. splice building ports;
6. shortest path;
7. simplification;
8. depth feasibility.

---

# 28. Ограничение graph size

```text
MAX_CANDIDATES = 1600
```

Если obstacle points больше, выполняется radial sampling вокруг центра множества, а не выбор произвольного prefix.

---

# 29. Sparse edges

На каждый node рассматриваются 64 ближайших кандидата.

Пространство делится на 16 angular sectors.

На сектор проверяется до 6 candidates и принимается до 2 legal edges.

Это уменьшает число expensive exact geometry checks.

---

# 30. Scenario-level caching

Static graph кешируется по `(DN, RunMode)`.

Также кешируются baseline routes без новой сети. Если baseline не пересекает `occupied`, повторный поиск не выполняется.

---

# 31. Shortest-path state

Состояние поиска:

```text
(node, previousNode)
```

Previous node нужен для контроля следующего поворота.

---

# 32. Ограничение поворота

Если:

```text
incoming · outgoing < 0
```

поворот больше 90° и переход запрещается.

---

# 33. Bend penalty

Поворот более примерно 5° получает routing penalty, эквивалентный 8 м длины. Это эвристика маршрутизации, а не рублёвая статья стоимости.

---

# 34. Priority search

Priority queue использует:

```text
priority = accumulatedCost + remainingGraphDistanceIgnoringTurns
```

Поиск A*-подобный.

---

# 35. Simplification

После поиска проверяются тройки `A-B-C`. Если `A-C` legal и не нарушает направление/entry rule, `B` удаляется.

В DEPTH после simplification повторно проверяется профиль глубины.

---

# 36. SharedNetworkBuilder

Строит shared forest, в котором terminal может подключаться:

1. к existing network;
2. к existing chamber;
3. к новой chamber;
4. к точке на уже построенном новом edge.

Это позволяет нескольким ОКС использовать общие trunk segments.

---

# 37. Начальное состояние

```text
edges = empty
roots = empty
unconnected = all terminals
```

`PlanEvaluator` сразу учитывает штрафы всех неподключённых ОКС.

---

# 38. Tie frontier

Изначально рассматривается до 24 root candidates.

Если подключения нет:

```text
24 → 96 → все доступные candidates
```

---

# 39. Attachment candidates

Для terminal рассматриваются:

- root tie;
- internal node с degree <4;
- projection на существующий plan edge.

При подключении в середину edge создаётся новая `CHAMBER`, а edge делится.

---

# 40. Оценка attachment

Для каждого attachment:

1. вычисляется DN;
2. строится route;
3. копируется plan;
4. добавляется terminal;
5. пересчитывается полный plan;
6. запускается `EngineeringValidator`.

Недопустимые candidates удаляются.

---

# 41. Выбор подключения

Выбирается candidate с минимальным приростом полного score с поправкой на снятый unconnected penalty.

---

# 42. Flow aggregation

`FlowDiameterCalculator` / `PipeSizing`:

```text
flow(terminal) = demand
flow(internal node) = Σ flow(children)
flow(edge) = flow(edge.child)
```

---

# 43. Минимальный DN

Первичный:

```text
DN(edge) = minForFlow(flow(edge))
```

---

# 44. Монотонность DN

По направлению к root диаметр не уменьшается. Если upstream DN меньше downstream requirement, он повышается.

---

# 45. Ограничение maxLength

Для непрерывной части с одинаковым расчётным расходом:

```text
runLength = Σ length
requiredFlow = max flow
```

Подбирается первый DN, для которого:

```text
capacity >= requiredFlow
maxLength >= runLength
DN >= current DN
```

Итерации продолжаются до convergence.

---

# 46. Segment cost

`SegmentCostModel`:

```text
cost = horizontalLength × newCost(DN) × specialFactor × averageDepthFactor
```

Special factors:

```text
road          1.60
tram_tracks   1.75
gas_pipeline  1.25
power_cable   1.15
heat_network  1.05
```

---

# 47. Tie-in и chamber cost

Подключение нового участка к существующей камере добавляет 5 млн руб.

Tie к existing pipe материализуется новой камерой в выбранной точке и учитывается в chamber construction cost.

---

# 48. Итоговая стоимость текущей модели

```text
calculatedCost =
    constructionCost
  + chamberCost
  + tieInCost
  + unconnectedPenalty
```

Reconstruction totals существуют как поля, но в текущем `PlanEvaluator` равны нулю.

---

# 49. JunctionRefiner

Для каждой новой камеры выполняется локальный поиск с шагами:

```text
24, 12, 6, 3, 1 m
```

На каждом шаге проверяются 8 направлений и до 3 passes.

Изменение принимается только при меньшем полном `S` и успешной validation.

---

# 50. PlanLocalSearch

Для каждого edge проверяется direct segment parent→child.

Он сохраняется только если короче, проходит полный validator и уменьшает `S`.

---

# 51. JointPlanRefiner

Совместно оптимизирует positions всех movable nodes.

Resolution schedule:

```text
12 → 3 → 1 m
```

Один проход на каждом уровне.

---

# 52. Candidate positions

Для `CHAMBER`: текущая точка + 8 точек на окружности радиуса `step`.

Для `TIE_IN` на existing pipe: current chainage и ±`step`.

Terminal фиксирован.

---

# 53. Edge cost matrix

Для каждой пары positions соседних nodes рассчитывается:

```text
cost[parentPosition][childPosition]
```

Проверяются исходная shaped polyline и direct line. Illegal pair получает `+∞`.

---

# 54. JointChamberMilp

Логическая binary model:

```text
x[node,candidate]
y[parent,left,child,right]
```

Но topology является rooted tree, поэтому используется специализированный точный dynamic programming solver вместо внешнего MILP solver.

---

# 55. Tree DP

Для каждого node/candidate хранится top-k subtree solutions.

Leaf имеет стоимость 0.

Internal node объединяет:

```text
edgeCost(parentCandidate, childCandidate)
+ childSubtreeCost
```

Невозможные пары пропускаются.

---

# 56. Top-k merge

Чтобы получить k smallest sums по child branches, используется priority queue по tuple indices.

Начало: `[0,0,...,0]`.

После извлечения состояния генерируются соседи увеличением одного индекса.

Limit = 8 assignments.

---

# 57. Граница точности solver

Solver точен для фиксированных:

- tree topology;
- candidate positions;
- cost matrices.

Он не доказывает глобальный минимум непрерывной геометрической задачи.

---

# 58. Принятие assignment

Каждый assignment материализуется в полный `NetworkPlan`, после чего заново рассчитываются flow, DN, cost, score и выполняется validation.

Сохраняется только strict improvement.

---

# 59. Reconnect leaves

Ранее подключённый terminal может быть отсоединён и подключён заново к trunk, появившемуся позднее.

Алгоритм удаляет leaf, чистит пустых ancestors, снова генерирует attachments и принимает только улучшение полного `S`.

---

# 60. Multi-start

```text
attempts = competition.optimizer.maxStarts (default: 3)
```

По умолчанию выполняются три старта.

Можно явно настроить до восьми стартов; для конкурсного набора проверяется получение трёх различных вариантов.

Default random seed: `20260922`.

---

# 61. Цикл одного seed

```text
plan = SharedNetworkBuilder.build
JunctionRefiner.improve
JointPlanRefiner.improve
archive

repeat:
    reconnectLeaves
    PlanLocalSearch
    JointPlanRefiner
    archive
    stop if score did not improve
```

Один раунд переподключения для каждого режима при включённом refine.

---

# 62. Базовый 3D-профиль

```text
normal depth = 3.0 m
minimum depth = 0.7 m
maximum slope = 0.10
```

У каждого plan node глубина должна вернуться к 3 м.

---

# 63. Depth factor

```text
depthFactor(d) = 1.0, d <= 3
                 1 + 0.10 × (d - 3), d > 3
```

---

# 64. Utility crossings

Газопровод:

```text
top = 2.8 m
height = 0.4 m
gap = 0.2 m
```

Силовой кабель:

```text
top = 2.7 m
height = 0.2 m
gap = 0.5 m
```

Existing heat network:

```text
top = 3.0 m
height = pairHeight(existing DN)
gap = 0.5 m
```

---

# 65. Проход сверху и снизу

Above:

```text
aboveDepth = obstacleTop - gap - pairHeight(new DN)
```

Допустим, если `aboveDepth >= 0.7` и хватает длины для ramps.

Otherwise:

```text
belowDepth = obstacleTop + obstacleHeight + gap
```

---

# 66. Plateau и ramp

Plateau: 2 м до + 2 м после crossing = 4 м.

Уклон:

```text
|Δdepth| / horizontalLength <= 0.10
```

Минимальная длина ramp:

```text
|targetDepth - 3| / 0.10
```

---

# 67. Reconcile depth constraints

`SegmentCostModel.reconcileDepths()` согласует перекрывающиеся ramps/plateaus, чтобы профиль не имел разрывов и противоречивых shallow/deep требований.

---

# 68. DepthProfileValidator

Проверяет:

- start depth = 3;
- end depth = 3;
- depth ≥0.7;
- continuity;
- slope ≤0.10;
- road depth ≥1.0;
- tram depth ≥1.2;
- utility clearance;
- 4 m plateau.

---

# 69. EngineeringValidator: topology

Проверяет:

- degree ≤4;
- terminal — leaf;
- tie root без parent;
- нет orphan nodes;
- degree existing chamber ≤4;
- 10 m chamber rule;
- connected + unconnected = все input OKS;
- отсутствие циклов.

---

# 70. EngineeringValidator: DN

Повторно запускается `PipeSizing.requiredDiameters()`.

Stored DN обязан совпасть с независимо рассчитанным required DN.

---

# 71. EngineeringValidator: geometry

Проверяются endpoints, restrictions, special crossings, turn ≤90°, а также попарные пересечения новых edges.

---

# 72. EngineeringValidator: score

Полностью пересчитываются cost, length и official score и сравниваются с plan.

---

# 73. VariantDiversity

Различие оценивается по трём наборам:

1. tie-ins;
2. topology/grouping;
3. corridor cells.

Corridor cell size = 12 м.

---

# 74. Material difference

Plans различны, если выполняется хотя бы одно:

```text
tie-in Jaccard distance >= 0.15
OR topology Jaccard distance >= 0.15
OR corridor Jaccard distance >= 0.25
```

---

# 75. Coverage priority

Перед portfolio selection остаются только candidates с минимальным количеством `unconnected`.

Если найдено fully connected решение, partial candidates не входят в финальный отбор.

---

# 76. MilpPortfolioSelector

Перебираются singletons, compatible pairs и triples.

Критерии:

1. максимальное число вариантов;
2. лучший individual `S`;
3. минимальная сумма `S`.

Финальные ID:

```text
2d_1, 2d_2, 2d_3
3d_1, 3d_2, 3d_3
```

---

# 77. GeoJSON export

`GeoJsonResultWriter` экспортирует:

- `heat_network`;
- `heat_chamber`;
- `technical_node`;
- `variant_summary`.

Один logical edge может быть разбит на несколько output features из-за special sections и depth breakpoints.

---

# 78. 2D/3D export

2D:

```text
[longitude, latitude]
```

3D:

```text
[longitude, latitude, -depth]
```

Depth между концами piece линейно интерполируется.

---

# 79. Variant summary

Содержит:

- construction cost;
- chamber construction cost;
- tie-in cost;
- reconstruction totals;
- unconnected penalty;
- calculated cost;
- new network length;
- total length;
- score;
- unconnected IDs.

---

# 80. OutputGeoJsonValidator

Проверяет уже записанный файл:

- `FeatureCollection`;
- обязательные поля;
- допустимые output object types;
- coordinate dimensions;
- Z/depth consistency;
- unique IDs;
- total cost;
- total length;
- score.

---

# 81. End-to-end pseudocode

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

        if final attempt and fewer than 3 diverse plans and attempts < 8:
            extend attempts

    if candidates empty:
        fail

    keep only best coverage
    selected = exact portfolio selection, max 3
    sort by score
    assign variant IDs
    export
    validate exported GeoJSON
    return selected
```

---

# 82. Shared network pseudocode

```text
function buildSharedForest(snapshot, mode, seed):

    plan = empty
    unconnected = all terminals

    prepare tie candidate lists

    while unconnected not empty:
        best = none

        order terminals by proximity × deterministic seed factor

        for terminal:
            attachments = existing ties + reusable plan nodes + plan-edge points

            for attachment:
                route = sparseVisibilityRoute(...)
                if absent: continue

                candidate = attach(copy(plan), route)
                evaluate(candidate)
                validate(candidate)

                if valid:
                    keep best increment

            if best found:
                break

        if no best:
            expand tie frontier
            if exhaustive: break
            continue

        plan = best

    return validated plan
```

---

# 83. Routing pseudocode

```text
function route(start, goal, DN, mode, occupied):

    if cached baseline exists and not blocked:
        return baseline

    if direct segment legal and depth feasible:
        return direct

    ports = nearest facade approaches(goal)
    graph = copy(cachedVisibilityGraph(DN, mode))

    add start, goal, ports, escape nodes
    connect legal endpoint edges

    path = shortestPath(state=(node, previousNode))
    if absent: return none

    simplify(path)

    if DEPTH and simplified profile infeasible:
        try unsimplified path

    return feasible path
```

---

# 84. Joint refinement pseudocode

```text
function jointlyRefine(plan):

    for step in [12,3,1]:
        one pass:

            positions[node] = legal candidate positions

            for every edge:
                build pairwise cost matrix

            assignments = exactTopKTreeDP(limit=8)
            best = plan

            for assignment:
                candidate = materialize full plan
                recalculate flow, DN, cost, score
                validate

                if valid and score improves:
                    best = candidate

            if unchanged:
                break

            plan = best

    return plan
```

---

# 85. Производительность

Используются:

- streaming ingest;
- batch persistence;
- bbox filtering;
- spatial index;
- prepared geometries;
- exact distance index;
- DN-specific buffers;
- scenario-level visibility graph cache;
- baseline route cache;
- segment assessment cache;
- cost cache;
- streaming export.

Полный visibility graph `O(V²)` не строится; используются nearest candidates и angular sectors.

---

# 86. Детерминизм и generalization

Production-код не использует fixed IDs, fixed coordinates, ready-made GeoJSON или внешний Python solver.

Default seed: `20260922`.

`GeneralizationSmokeTest` запускает алгоритм на synthetic scenarios с другими ID, UUID и координатами, проверяя оба режима.

---

# 87. Ограничения метода

Алгоритм не доказывает глобальный optimum непрерывной задачи.

Он сочетает:

- multi-start constructive search;
- exact-geometry routing;
- local search;
- exact discrete tree optimization;
- topology reconnection;
- diversity portfolio selection.

`JointChamberMilp` точен только для фиксированного candidate set и текущей tree topology.

---

# 88. Reconstruction

`ReconstructionEvaluator` присутствует в кодовой базе, но текущий `PlanEvaluator` устанавливает:

```text
reconstructionCost = 0
reconstructionLength = 0
chamberReconstructionCost = 0
```

Поэтому реконструкция существующей сети не входит в текущую objective и основной export.

---

# 89. Карта стадий на Java-классы

| Стадия | Класс |
|---|---|
| HTTP upload | `ScenarioController` |
| streaming ingest | `GeoJsonStreamingIngestor` |
| CRS | `CrsTransformer` |
| snapshot | `SnapshotLoader` |
| topology recovery | `ExistingTopologyResolver` |
| tie candidates | `TieCandidateGenerator` |
| shared network | `SharedNetworkBuilder` |
| routing | `SparseVisibilityRouter` |
| building entry | `BuildingEntry` |
| restrictions | `RouteConstraintEngine` |
| flow | `FlowDiameterCalculator` |
| DN sizing | `PipeSizing` |
| cost/depth | `SegmentCostModel` |
| total score | `PlanEvaluator` |
| junction refinement | `JunctionRefiner` |
| local search | `PlanLocalSearch` |
| joint refinement | `JointPlanRefiner` |
| exact tree solver | `JointChamberMilp` |
| diversity | `VariantDiversity` |
| portfolio | `MilpPortfolioSelector` |
| engineering validation | `EngineeringValidator` |
| depth validation | `DepthProfileValidator` |
| export | `GeoJsonResultWriter` |
| output validation | `OutputGeoJsonValidator` |
| orchestration | `OptimizationService` |

---

# 90. Ключевые инварианты

## Топология

```text
acyclic
degree <= 4
terminal is leaf
root is tie-in
no orphan node
connected ∪ unconnected = all input OKS
```

## Геометрия

```text
no forbidden transit
no building transit except own terminal lead
no new-new crossing
no illegal overlap
turn <= 90°
clearances preserved
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
summary cost = sum object costs + penalties
summary length = sum network lengths
summary score = official formula
```

---

# 91. Условие успешного завершения

```text
optimizer produced plans
→ selected plans engineering-valid
→ GeoJSON written
→ OutputGeoJsonValidator returned no errors
→ run.status = DONE
```

При exception:

```text
run.status = ERROR
run.phase = ERROR
run.error = exception text
```

---

# 92. Команды проверки

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
# SPEC

## §G GOAL
Java service ! build valid 2D & depth heat-network plans from loaded GeoJSON only.

## §C CONSTRAINTS
- Java 11 Spring Boot app, API, DB, ingest & export ! stay.
- production, test & container ⊥ external prototype source, process, package or fixed artifact.
- topology, routes, DN, costs & score ! derive from loaded snapshot.
- output ! 3 distinct exact-valid variants on corrected task dataset; public API ! compatible.
- objective ! current participant clarifications: new construction, chambers, existing-chamber taps and penalties; no existing-network reconstruction.
- arbitrary input ! at most 3 variants; impossible distinct plans ⊥ fabricated.
- fixed config random seed ! repeatable planning; no input-specific geometry or IDs.

## §I INTERFACES
- api: `POST /api/v1/scenarios` multipart GeoJSON → scenario id
- api: `POST /api/v1/scenarios/{id}/runs?mode=2d|3d` → run id
- api: `GET /api/v1/runs/{id}` → status
- api: `GET /api/v1/runs/{id}/variants` → variants
- api: `GET /api/v1/runs/{id}/variants/{variantId}/validation` → validation
- api: `GET /api/v1/runs/{id}/export` → GeoJSON `FeatureCollection`

## §V INVARIANTS
V1: ∀ loaded snapshot → `UniversalOptimizer` builds from snapshot fields.
V2: production, tests, container & build ⊥ external prototype code, process, package & fixed artifact.
V3: ∀ `TWO_D` & `DEPTH` plan → termination, ≥1 variant & passed `EngineeringValidator`.
V4: ∀ output → `OutputGeoJsonValidator` passes & §I API shape stays compatible.
V5: topology, route, DN, cost & score ! input-derived; known IDs/coordinates ⊥ required.

V6: corrected task dataset → 3 materially distinct fully connected plans per mode; best 2D S < 13.4; best DEPTH S < best TWO_D S.
V7: DEPTH export → [longitude,latitude,-depth] at every vertex; plateau 4m, slope ≤0.10, minimum depth 0.7m, return depth 3m at nodes.
V8: exported object costs & lengths sum to summary; reconstruction totals are zero and no tie_in or reconstruction feature is emitted.
V9: corrected input missing flow → 0; missing upstream → source-oriented endpoint graph; missing chamber DN → maximum adjacent DN; explicit values ! preserved.

## §T TASKS
id|status|task|cites
T8|x|remove legacy prototype path & add loaded-snapshot regression|V1,V2,V3,V5

T9|~|joint chamber & tie optimisation, topology reconnect, diverse portfolio|V1,V2,V3,V5,V6
T10|~|restore appendix costs & XYZ depth profiles, verify actual dataset export|V4,V7,V8,V9

## §B BUGS
id|date|cause|fix
B22|2026-09-28|loaded snapshot bypassed universal builder ∴ fixed artifact required|V1,V2
B23|2026-09-28|first-valid early exit & unused joint solver limited portfolio|V6
B24|2026-09-28|reconstruction disabled by participant clarification; pipe taps represented by chamber|V7,V8

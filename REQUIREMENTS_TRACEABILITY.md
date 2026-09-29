# Requirements traceability

| Требование | Реализация |
|---|---|
| Java 11 | `pom.xml` / `<java.version>11</java.version>` |
| Spring Boot 2.6.3 | parent в `pom.xml` |
| Spring Data | `spring-boot-starter-data-jpa`, repositories |
| PostgreSQL | datasource + `docker-compose.yml` |
| springdoc-openapi-ui 1.7.0 | `pom.xml`, `/swagger-ui.html` |
| docker-compose | `docker-compose.yml` |
| 16 GB-friendly / 3 GB input | temp disk + Jackson streaming + DB persistence |
| output ≤500 MB support | streaming `JsonGenerator`, file response |
| EPSG:4326 input/output | `CrsTransformer`, `GeoJsonResultWriter` |
| EPSG:32637 calculations | `CrsTransformer` |
| source / network / chamber / OKS / restrictions | streaming schema parser |
| string IDs; numeric samples tolerated | scalar canonical ID parsing |
| all prospective OKS in one run | `UniversalOptimizer` |
| choose tie-ins automatically | `TieCandidateGenerator` |
| 10 m chamber rule | candidate canonicalization |
| joint or separate connection | shared forest attachments + topology reconnect |
| branches only through chambers | shared branch nodes are `CHAMBER` |
| degree ≤4 | generator guard + independent validator |
| no cycles | construction model + validator |
| no new-new crossings | router + validator |
| rational geometry | direction-aware A*, simplification, max 90° |
| flow aggregation | `FlowDiameterCalculator` |
| minimum sufficient DN | `RuleBook.minForFlow` |
| same-DN max length | branch-aware connected same-DN groups |
| at most one DN promotion | `minDn` + promotion guard + validator |
| existing-network reconstruction disabled by current clarification | `PlanEvaluator` sets reconstruction totals to zero; exporter emits no reconstruction features |
| selected point on existing network | new `heat_chamber` at the selected point |
| existing chamber connection count / four-branch limit | `ExistingChamberRules`, `EngineeringValidator` |
| tie-in 5M | `PlanEvaluator` / exporter |
| chamber scale | `RuleBook.chamberCost` |
| unconnected penalty | `RuleBook.unconnectedPenalty` |
| official 70/30 score | `RuleBook.officialScore` |
| up to 3 variants | `MilpPortfolioSelector`: maximum feasible cardinality, then best S and total S |
| structurally different variants | MILP pairwise hard constraints over `VariantDiversity` tie-in/topology/corridor distance |
| one combined output GeoJSON | `GeoJsonResultWriter` |
| exact output object types | writer methods per official type |
| one summary per variant | writer + `OutputGeoJsonValidator` |
| save partial result | `unconnected` set and penalties |
| independent Stage-2 depth mode | `RunMode.DEPTH`, separate optimization run |
| normal depth 3 m | `SegmentCostModel` |
| min depth 0.7 m | depth validator |
| max slope 0.10 | profile + validator |
| depth cost | `RuleBook.depthFactor` |
| special crossings | `RestrictionRules`, `SegmentCostModel` |
| independent validation | `EngineeringValidator` + `OutputGeoJsonValidator` |
| no manual geometry editing | end-to-end automatic API |
| no dataset-specific coordinates/IDs | `scripts/verify_project.sh` |

| simultaneous chamber/tie movement | `JointPlanRefiner` + exact `JointChamberMilp` on candidate tree |
| XYZ at every depth vertex | `GeoJsonResultWriter`, Z = negative depth, no loss through CRS transform |
| 4m plateau and return to 3m | `DepthProfileValidator`, independent vertical clearance checks |
| exported cost/length reconciliation | `OutputGeoJsonValidator.checkTotals` |
| three full-coverage variants and S thresholds on corrected dataset | `DatasetQualityTest` |

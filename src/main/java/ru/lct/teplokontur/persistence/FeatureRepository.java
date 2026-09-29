package ru.lct.teplokontur.persistence;
import org.springframework.data.jpa.repository.*;import org.springframework.data.repository.query.Param;import java.util.*;import java.util.stream.Stream;
public interface FeatureRepository extends JpaRepository<FeatureEntity,Long> {
  @Query("select f from FeatureEntity f where f.scenarioId=:sid and f.objectType=:type order by f.rowId")
  List<FeatureEntity> findByScenarioIdAndObjectType(@Param("sid") Long scenarioId,@Param("type") String objectType);
  List<FeatureEntity> findByScenarioIdOrderByRowIdAsc(Long scenarioId);
  Optional<FeatureEntity> findByScenarioIdAndFeatureId(Long scenarioId,String featureId);
  long countByScenarioId(Long scenarioId);
  @Query("select f from FeatureEntity f where f.scenarioId=:sid and f.maxX>=:minX and f.minX<=:maxX and f.maxY>=:minY and f.minY<=:maxY order by f.rowId")
  List<FeatureEntity> bbox(@Param("sid")Long sid,@Param("minX")double minX,@Param("minY")double minY,@Param("maxX")double maxX,@Param("maxY")double maxY);
  @Query("select f from FeatureEntity f where f.scenarioId=:sid") Stream<FeatureEntity> streamAll(@Param("sid")Long sid);
}

package ru.lct.teplokontur.persistence;
import javax.persistence.*;
@Entity @Table(name="scenario_feature",indexes={@Index(name="idx_feature_scenario_id",columnList="scenario_id,feature_id",unique=true),@Index(name="idx_feature_scenario_type",columnList="scenario_id,object_type"),@Index(name="idx_feature_bbox",columnList="scenario_id,min_x,max_x,min_y,max_y")})
public class FeatureEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long rowId;
 @Column(name="scenario_id") public Long scenarioId; @Column(name="feature_id",length=512) public String featureId; @Column(name="object_type",length=64) public String objectType; @Column(name="restriction_type",length=64) public String restrictionType;
 @Lob public byte[] geometryWkb; @Lob public String propertiesJson; @Lob public String geometryJson;
 @Column(name="min_x") public double minX; @Column(name="min_y") public double minY; @Column(name="max_x") public double maxX; @Column(name="max_y") public double maxY;
 public Integer diameter; public Double flowTph; @Column(length=512) public String upstreamObjectId; @Column(length=512) public String oksId;
}

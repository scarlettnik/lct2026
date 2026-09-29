package ru.lct.teplokontur.ingest;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.teplokontur.domain.RestrictionRules;
import ru.lct.teplokontur.persistence.*;

import java.io.*;import java.util.*;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

@Service
public class GeoJsonStreamingIngestor {
  public static final long MAX_UPLOAD_BYTES=3L*1024*1024*1024;
  @PersistenceContext private EntityManager entityManager;
  private final ObjectMapper om; private final ScenarioRepository scenarios; private final FeatureRepository features; private final CrsTransformer crs;
  public GeoJsonStreamingIngestor(ObjectMapper om,ScenarioRepository scenarios,FeatureRepository features,CrsTransformer crs){this.om=om;this.scenarios=scenarios;this.features=features;this.crs=crs;}

  @Transactional(rollbackFor=IOException.class)
  public ScenarioEntity ingest(InputStream input,String name) throws IOException {
    ScenarioEntity s=new ScenarioEntity();s.name=name==null?"scenario":name;s.status="INGESTING";s=scenarios.save(s);
    long count=0; int sources=0;
    try(JsonParser p=om.getFactory().createParser(new LimitedUpload(input))){
      p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      expect(p.nextToken(),JsonToken.START_OBJECT,"FeatureCollection object");
      boolean foundFeatures=false,foundType=false;
      while(p.nextToken()!=JsonToken.END_OBJECT){expect(p.currentToken(),JsonToken.FIELD_NAME,"root field");String field=p.currentName();p.nextToken();
        if("type".equals(field)){foundType=true;if(!"FeatureCollection".equals(p.getValueAsString()))throw new IllegalArgumentException("Root type must be FeatureCollection");}
        else if("features".equals(field)){foundFeatures=true;expect(p.currentToken(),JsonToken.START_ARRAY,"features array");
          List<FeatureEntity> batch=new ArrayList<>(200);
          while(p.nextToken()!=JsonToken.END_ARRAY){expect(p.currentToken(),JsonToken.START_OBJECT,"Feature object");JsonNode f=om.readTree(p);FeatureEntity e=parseFeature(s.id,f);if("source".equals(e.objectType))sources++;batch.add(e);count++;if(batch.size()>=200)persistBatch(batch);}
          if(!batch.isEmpty())persistBatch(batch);
        } else p.skipChildren();
      }
      if(!foundFeatures||!foundType)throw new IllegalArgumentException("FeatureCollection type and features[] required");
      if(p.nextToken()!=null)throw new IllegalArgumentException("Unexpected content after FeatureCollection");
    }
    if(sources!=1)throw new IllegalArgumentException("Exactly one source required, found "+sources);
    validateReferences(s.id);
    s.featureCount=count;s.status="READY";return scenarios.save(s);
  }
  private void persistBatch(List<FeatureEntity> batch) {
    features.saveAllAndFlush(batch);
    entityManager.clear();
    batch.clear();
  }

  private FeatureEntity parseFeature(Long sid,JsonNode f){
    if(!"Feature".equals(f.path("type").asText()))throw new IllegalArgumentException("Every item must be Feature");
    JsonNode pr=f.path("properties");String id=scalar(pr.get("id"));String type=text(pr,"object_type");
    if(id==null||id.isBlank())throw new IllegalArgumentException("Feature id missing");
    Geometry g=GeoJsonGeometry.parse(f.get("geometry"));if(g==null||!g.isValid())throw new IllegalArgumentException("Invalid geometry for "+id);
    validateGeometryType(type,g,id);
    Geometry utm=crs.toUtm(g);Envelope b=utm.getEnvelopeInternal();FeatureEntity e=new FeatureEntity();e.scenarioId=sid;e.featureId=id;e.objectType=type;e.geometryWkb=new WKBWriter().write(utm);e.propertiesJson=pr.toString();e.geometryJson=f.get("geometry").toString();e.minX=b.getMinX();e.minY=b.getMinY();e.maxX=b.getMaxX();e.maxY=b.getMaxY();
    e.diameter=nullableInt(pr.get("diameter"));e.flowTph=nullableDouble(pr.get("flow_tph"));e.upstreamObjectId=scalar(pr.get("upstream_object_id"));e.oksId=scalar(pr.get("oks_id"));e.restrictionType=pr.hasNonNull("restriction_type")?pr.get("restriction_type").asText():null;
    if("restriction".equals(type)&&!RestrictionRules.known(e.restrictionType))throw new IllegalArgumentException("Unknown restriction_type "+e.restrictionType+" at "+id);
    if("heat_chamber".equals(type)&&e.diameter==null)e.diameter=200;
    if("heat_network".equals(type)&&e.diameter==null)throw new IllegalArgumentException("diameter missing at "+id);
    if("heat_network".equals(type)&&e.flowTph==null)e.flowTph=0.;
    if("oks_future".equals(type)&&e.flowTph==null)throw new IllegalArgumentException("flow_tph missing at "+id);
    if("oks_connection_point".equals(type)&&e.oksId==null){if(e.flowTph==null)throw new IllegalArgumentException("oks_id or flow_tph missing at "+id);e.oksId=id;}
    return e;
  }
  private void validateReferences(Long sid){
    rejectReference(sid,"select f.feature_id from scenario_feature f where f.scenario_id=:sid and f.oks_id is not null "
        +"and not (f.object_type='oks_connection_point' and f.oks_id=f.feature_id and f.flow_tph is not null) "
        +"and not exists (select 1 from scenario_feature o where o.scenario_id=:sid and o.feature_id=f.oks_id and o.object_type='oks_future')",
        "Invalid oks_id at ");
    // UNION, rather than UNION ALL, bounds traversal even when the input contains cycles.
    rejectReference(sid,"with recursive reachable(feature_id) as ("
        +"select feature_id from scenario_feature where scenario_id=:sid and object_type='source' union "
        +"select f.feature_id from scenario_feature f join reachable r on f.upstream_object_id=r.feature_id "
        +"where f.scenario_id=:sid and f.object_type in ('heat_network','heat_chamber')) "
        +"select f.feature_id from scenario_feature f where f.scenario_id=:sid "
        +"and f.object_type in ('heat_network','heat_chamber') and f.upstream_object_id is not null "
        +"and not exists (select 1 from reachable r where r.feature_id=f.feature_id)",
        "Existing network reference is missing, cyclic or does not reach source at ");
  }

  private void rejectReference(Long sid,String sql,String message) {
    List<?> invalid=entityManager.createNativeQuery(sql).setParameter("sid",sid).setMaxResults(1).getResultList();
    if(!invalid.isEmpty())throw new IllegalArgumentException(message+invalid.get(0));
  }

  private static final class LimitedUpload extends FilterInputStream {
    private long count;
    LimitedUpload(InputStream input){super(input);}
    private void consumed(int n){if(n>0&&(count+=n)>MAX_UPLOAD_BYTES)throw new IllegalArgumentException("Upload exceeds 3 GiB");}
    @Override public int read() throws IOException {int b=in.read();if(b>=0)consumed(1);return b;}
    @Override public int read(byte[] bytes,int offset,int length) throws IOException {int n=in.read(bytes,offset,length);consumed(n);return n;}
  }
  private static void validateGeometryType(String t,Geometry g,String id){boolean ok;if("source".equals(t)||"heat_chamber".equals(t)||"oks_connection_point".equals(t))ok=g instanceof Point;else if("heat_network".equals(t))ok=g instanceof LineString;else if("oks_future".equals(t)||"oks_existing".equals(t))ok=g instanceof Polygon||g instanceof MultiPolygon;else if("restriction".equals(t))ok=true;else ok=false;if(!ok)throw new IllegalArgumentException("Unexpected object_type/geometry at "+id+": "+t+"/"+g.getGeometryType());}
  private static String scalar(JsonNode n){if(n==null||n.isNull())return null;if(n.isTextual()||n.isNumber()||n.isBoolean())return n.asText();throw new IllegalArgumentException("ID/reference must be JSON scalar");}
  private static String text(JsonNode n,String f){if(!n.hasNonNull(f))throw new IllegalArgumentException(f+" missing");return n.get(f).asText();}
  private static Integer nullableInt(JsonNode n){return n==null||n.isNull()?null:n.asInt();}private static Double nullableDouble(JsonNode n){return n==null||n.isNull()?null:n.asDouble();}
  private static void expect(JsonToken got,JsonToken want,String msg){if(got!=want)throw new IllegalArgumentException("Expected "+msg+", got "+got);}
}

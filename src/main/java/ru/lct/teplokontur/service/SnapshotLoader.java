package ru.lct.teplokontur.service;
import org.locationtech.jts.geom.*;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import ru.lct.teplokontur.config.CompetitionProperties;import ru.lct.teplokontur.domain.*;import ru.lct.teplokontur.engineering.GeometryStore;import ru.lct.teplokontur.persistence.*;import java.util.*;
@Service
public class SnapshotLoader {
 private final com.fasterxml.jackson.databind.ObjectMapper attributes=new com.fasterxml.jackson.databind.ObjectMapper();
 private final FeatureRepository repo;private final GeometryStore geom;private final CompetitionProperties props;
 public SnapshotLoader(FeatureRepository repo,GeometryStore geom,CompetitionProperties props){this.repo=repo;this.geom=geom;this.props=props;}
 @Transactional(readOnly=true) public InputSnapshot load(long sid){InputSnapshot s=new InputSnapshot(sid);Map<String,Double> flows=new HashMap<>();for(FeatureEntity f:repo.findByScenarioIdAndObjectType(sid,"oks_future"))flows.put(f.featureId,f.flowTph);
  for(FeatureEntity f:repo.findByScenarioIdOrderByRowIdAsc(sid)){InputSnapshot.InputFeature x=new InputSnapshot.InputFeature();x.sequence=f.rowId;x.id=f.featureId;x.objectType=f.objectType;x.restrictionType=f.restrictionType;x.propertiesJson=f.propertiesJson;x.geometryJson=f.geometryJson;s.features.add(x);}
  Envelope work=new Envelope();for(FeatureEntity f:repo.findByScenarioIdAndObjectType(sid,"oks_connection_point")){InputSnapshot.Terminal t=new InputSnapshot.Terminal();t.oksId=f.oksId;t.pointId=f.featureId;t.point=(Point)geom.read(f);t.flow=flows.containsKey(f.oksId)?flows.get(f.oksId):f.flowTph==null?0.:f.flowTph;s.terminals.add(t);work.expandToInclude(t.point.getCoordinate());}
  work.expandBy(props.getRouting().getWorkingSetPaddingM());
  for(FeatureEntity f:repo.findByScenarioIdAndObjectType(sid,"source"))s.source=(Point)geom.read(f);
  // Existing network topology is kept because upstream reconstruction may leave the local routing envelope.
  for(FeatureEntity f:repo.findByScenarioIdAndObjectType(sid,"heat_network")){InputSnapshot.ExistingEdge e=new InputSnapshot.ExistingEdge();e.id=f.featureId;e.upstream=f.upstreamObjectId;e.dn=f.diameter;e.flow=f.flowTph;e.geometry=(LineString)geom.read(f);s.network.add(e);}
  for(FeatureEntity f:repo.findByScenarioIdAndObjectType(sid,"heat_chamber")){InputSnapshot.ExistingChamber c=new InputSnapshot.ExistingChamber();c.id=f.featureId;c.upstream=f.upstreamObjectId;c.dn=f.diameter;c.point=(Point)geom.read(f);s.chambers.add(c);}
  if(!work.isNull())for(FeatureEntity f:repo.bbox(sid,work.getMinX(),work.getMinY(),work.getMaxX(),work.getMaxY())){if("restriction".equals(f.objectType)){InputSnapshot.Restriction r=new InputSnapshot.Restriction();r.id=f.featureId;r.type="oks".equals(f.restrictionType)?"oks_existing":f.restrictionType;r.geometry=geom.read(f);s.restrictions.add(r);}else if("oks_existing".equals(f.objectType)){InputSnapshot.Restriction r=new InputSnapshot.Restriction();r.id=f.featureId;r.type="oks_existing";r.geometry=geom.read(f);s.restrictions.add(r);}}
  String sourceId=null;Set<String> missingDiameters=new HashSet<>();
  for(InputSnapshot.InputFeature feature:s.features){if("source".equals(feature.objectType))sourceId=feature.id;if("heat_chamber".equals(feature.objectType)){try{if(!attributes.readTree(feature.propertiesJson).hasNonNull("diameter"))missingDiameters.add(feature.id);}catch(java.io.IOException ex){throw new IllegalArgumentException("Invalid chamber attributes",ex);}}}
  new ru.lct.teplokontur.engineering.ExistingTopologyResolver().resolve(s,sourceId,missingDiameters);
  return s;}
}

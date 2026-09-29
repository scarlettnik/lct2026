package ru.lct.teplokontur.routing;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;
import ru.lct.teplokontur.domain.DnSpec;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.LineCalculationKey;
import ru.lct.teplokontur.domain.RestrictionRule;
import ru.lct.teplokontur.domain.RestrictionRules;
import ru.lct.teplokontur.domain.RuleBook;
import ru.lct.teplokontur.domain.RunMode;

public class RouteConstraintEngine {
  private final InputSnapshot s;
  private final GeometryFactory gf=new GeometryFactory();
  private final STRtree restrictions=new STRtree();
  private final STRtree network=new STRtree();
  private double maxNetworkHalfWidth;
  private final Map<InputSnapshot.ExistingEdge, PreparedGeometry> preparedNetwork=new IdentityHashMap<>();
  private final Map<InputSnapshot.Restriction, PreparedGeometry> preparedRestrictions=new IdentityHashMap<>();
  private final Map<InputSnapshot.Restriction, IndexedFacetDistance> restrictionDistances=new IdentityHashMap<>();
  private final Map<InputSnapshot.ExistingEdge, IndexedFacetDistance> networkDistances=new IdentityHashMap<>();
  private final Map<InputSnapshot.Restriction, Geometry> buildingFacades=new IdentityHashMap<>();
  private final Map<InputSnapshot.Restriction, IndexedFacetDistance> buildingDistances=new IdentityHashMap<>();
  private final Map<Geometry,List<LineSegment>> crossingSegments=new IdentityHashMap<>();
  private final Map<Geometry,BuildingEntry.Area> buildingAreas=new IdentityHashMap<>();
  /**
   * Buffer construction is by far the most expensive operation in a visibility
   * search.  A restriction's forbidden envelope depends only on its identity
   * and the routed DN, so prepare it once and reuse it for every candidate edge.
   */
  private final Map<Integer, Map<InputSnapshot.Restriction, PreparedGeometry>> forbiddenByDiameter=new HashMap<>();
  private final Map<LineCalculationKey,RouteAssessment> staticAssessments=new LinkedHashMap<LineCalculationKey,RouteAssessment>(256,.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<LineCalculationKey,RouteAssessment> entry){return size()>32768;}
  };
  private final Map<List<Object>,Boolean> buildingLeads=new LinkedHashMap<List<Object>,Boolean>(64,.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<List<Object>,Boolean> entry){return size()>8192;}
  };

  public RouteConstraintEngine(InputSnapshot s){
    this.s=s;
    for(InputSnapshot.Restriction r:s.restrictions){
      restrictions.insert(r.geometry.getEnvelopeInternal(),r);
      preparedRestrictions.put(r,PreparedGeometryFactory.prepare(r.geometry));
      restrictionDistances.put(r,new IndexedFacetDistance(r.geometry));
      if("oks_existing".equals(r.type)) {
        Geometry facade=BuildingEntry.exteriorBoundary(r.geometry);
        buildingFacades.put(r,facade);
        buildingDistances.put(r,new IndexedFacetDistance(facade));
      }
    }
    for(int i=0;i<s.network.size();i++) {
      InputSnapshot.ExistingEdge edge=s.network.get(i);
      network.insert(edge.geometry.getEnvelopeInternal(),i);
      maxNetworkHalfWidth=Math.max(maxNetworkHalfWidth,RuleBook.byDn(edge.dn).pairWidth/2.0);
      preparedNetwork.put(edge,PreparedGeometryFactory.prepare(edge.geometry));
      networkDistances.put(edge,new IndexedFacetDistance(edge.geometry));
    }
    restrictions.build();network.build();
  }

  public RouteAssessment assess(LineString seg,int dn,Collection<LineString> newLines,Coordinate allowedTouch,RunMode mode){
    return assess(seg,dn,newLines,allowedTouch,mode,null);
  }

  public RouteAssessment assess(LineString seg,int dn,Collection<LineString> newLines,Coordinate allowedTouch,
                                RunMode mode,Coordinate allowedBuildingTerminal){
    return assess(seg,dn,newLines,allowedTouch,mode,allowedBuildingTerminal,null);
  }

  public RouteAssessment assess(LineString seg,int dn,Collection<LineString> newLines,Coordinate allowedTouch,
                                RunMode mode,Coordinate allowedBuildingTerminal,Coordinate buildingEntryPort){
    // A tie outside this segment's envelope cannot affect its static assessment.
    Coordinate staticTouch=allowedTouch;
    if(staticTouch!=null){Envelope touchRange=new Envelope(seg.getEnvelopeInternal());touchRange.expandBy(.2);
      if(!touchRange.contains(staticTouch))staticTouch=null;}
    LineCalculationKey key=new LineCalculationKey(seg,dn,mode.ordinal(),
        x(staticTouch),y(staticTouch),x(allowedBuildingTerminal),y(allowedBuildingTerminal),x(buildingEntryPort),y(buildingEntryPort));
    RouteAssessment assessment=staticAssessments.get(key);
    if(assessment==null){assessment=assessStatic(seg,dn,staticTouch,mode,allowedBuildingTerminal,buildingEntryPort);staticAssessments.put(key,assessment);}
    if(!assessment.feasible)return assessment;
    // The already constructed network changes between candidates; never cache this part.
    if(newLines!=null)for(LineString l:newLines){
      if(!seg.getEnvelopeInternal().intersects(l.getEnvelopeInternal()))continue;
      Geometry inter=seg.intersection(l);if(inter.isEmpty())continue;
      for(Coordinate c:inter.getCoordinates()){if(allowedTouch!=null&&c.distance(allowedTouch)<.05)continue;return RouteAssessment.no("new-new-crossing");}
    }
    return assessment;
  }

  private static double x(Coordinate c){return c==null?Double.NaN:c.x;}
  private static double y(Coordinate c){return c==null?Double.NaN:c.y;}

  private RouteAssessment assessStatic(LineString seg,int dn,Coordinate allowedTouch,
                                      RunMode mode,Coordinate allowedBuildingTerminal,Coordinate buildingEntryPort){
    DnSpec spec=RuleBook.byDn(dn);double half=spec.pairWidth/2.0;double mult=1.0;
    Envelope nearby=new Envelope(seg.getEnvelopeInternal());nearby.expandBy(buildingClearance(dn)+half);for(Object candidate:restrictions.query(nearby)){InputSnapshot.Restriction r=(InputSnapshot.Restriction)candidate;
      double clr="oks_existing".equals(r.type)?buildingClearance(dn):Optional.ofNullable(RestrictionRules.get(r.type)).map(x->x.horizontalClearance).orElse(1.0);
      RestrictionRule rule=RestrictionRules.get(r.type);
      if("oks_existing".equals(r.type)||rule==null||!rule.crossingAllowed){
        // Route validation permits exactly one lead
        // from a terminal through *its own* building facade.  Treating that
        // building like an ordinary no-go object made every indoor terminal
        // unreachable after the Java port.  The exception is deliberately
        // narrow: only a snapshot terminal at an endpoint may leave the
        // containing building; a route may neither enter nor traverse it.
        if(forbidden(r,dn,clr+half).intersects(seg)&&!("oks_existing".equals(r.type)
            &&ownBuildingLead(r,seg,allowedBuildingTerminal,buildingEntryPort,dn,clr+half)))return RouteAssessment.no("forbidden:"+r.type);
        continue;
      }
      if(preparedRestrictions.get(r).intersects(seg)){
        Geometry intersection=(r.geometry.getDimension()==1||rule.minCrossAngle>0)?seg.intersection(r.geometry):null;
        if(r.geometry.getDimension()==1&&intersection.getLength()>1e-6)
          return RouteAssessment.no("overlap:"+r.type);
        // A chamber inside a road/tram polygon has no entrance crossing there.
        // Test the actual boundary crossings, including only the exit in that case.
        if(rule.minCrossAngle>0){Geometry crossings=r.geometry.getDimension()==2?seg.intersection(r.geometry.getBoundary()):intersection;for(Coordinate point:crossings.getCoordinates())
          if(crossingAngle(seg,r.geometry,point)+1e-6<rule.minCrossAngle)return RouteAssessment.no("cross-angle:"+r.type);}
        double dm=1.0;if(mode==RunMode.DEPTH&&("gas_pipeline".equals(r.type)||"power_cable".equals(r.type)))dm=RuleBook.depthFactor(3.4);mult=Math.max(mult,rule.specialFactor*dm);
      } else if(restrictionDistances.get(r).distance(seg)<clr+half+RestrictionRules.objectHalfWidth(r.type)-1e-6) return RouteAssessment.no("clearance:"+r.type);
    }
    // Existing heat network can be crossed independently, but overlap is forbidden. Ignore contact at a route endpoint (tie-in).
    Envelope networkRange=new Envelope(seg.getEnvelopeInternal());
    networkRange.expandBy(1.0+half+maxNetworkHalfWidth);
    for(int index:nearbyNetwork(networkRange)){
      InputSnapshot.ExistingEdge e=s.network.get(index);
      double requiredClearance=1.0+half+RuleBook.byDn(e.dn).pairWidth/2.0;
      if(seg.getEnvelopeInternal().distance(e.geometry.getEnvelopeInternal())>=requiredClearance)continue;
      if(!preparedNetwork.get(e).intersects(seg)){
        if(networkDistances.get(e).distance(seg)<requiredClearance-1e-6){
          boolean tieDeparture=allowedTouch!=null&&e.geometry.distance(gf.createPoint(allowedTouch))<.2&&seg.getCoordinateN(0).distance(allowedTouch)<.2;
          if(!tieDeparture)return RouteAssessment.no("clearance-existing-network");
        }
        continue;
      }
      Geometry inter=seg.intersection(e.geometry);
      if(inter.getDimension()>=1&&inter.getLength()>.05)return RouteAssessment.no("overlap-existing-network");
      for(Coordinate c:inter.getCoordinates()){
        if(allowedTouch!=null&&c.distance(allowedTouch)<.2)continue;
        double dm=mode==RunMode.DEPTH?RuleBook.depthFactor(3.0+RuleBook.byDn(e.dn).pairHeight+.5):1.0;
        mult=Math.max(mult,1.05*dm);
      }
    }
    return RouteAssessment.ok(mult);
  }

  @SuppressWarnings("unchecked")
  private List<Integer> nearbyNetwork(Envelope range) {
    List<Integer> result=new ArrayList<>(network.query(range));
    Collections.sort(result);
    return result;
  }

  int cachedForbiddenGeometryCount(){
    int count=0;
    for(Map<InputSnapshot.Restriction, PreparedGeometry> cached:forbiddenByDiameter.values())count+=cached.size();
    return count;
  }

  private PreparedGeometry forbidden(InputSnapshot.Restriction restriction,int dn,double distance){
    Map<InputSnapshot.Restriction, PreparedGeometry> cached=forbiddenByDiameter.computeIfAbsent(dn,key->new IdentityHashMap<>());
    return cached.computeIfAbsent(restriction,key->PreparedGeometryFactory.prepare(key.geometry.buffer(distance)));
  }

  private boolean ownBuildingLead(InputSnapshot.Restriction building,LineString seg,Coordinate allowedTerminal,
                                  Coordinate entryPort,int dn,double clearance){
    if(allowedTerminal==null||seg.getNumPoints()!=2)return false;
    GeometryFactory localFactory=building.geometry.getFactory();
    // The exception may apply only to the building which actually owns the
    // terminal. It must never weaken another building restriction nearby.
    if(!preparedRestrictions.get(building).covers(localFactory.createPoint(allowedTerminal)))return false;

    boolean terminalAtStart=seg.getCoordinateN(0).distance(allowedTerminal)<1e-5;
    boolean terminalAtEnd=seg.getCoordinateN(1).distance(allowedTerminal)<1e-5;

    // Segment immediately outside a verified facade port. It may travel inside
    // the setback envelope while escaping a narrow notch/courtyard, but it may
    // not cross or re-enter the actual building geometry.
    if(!terminalAtStart&&!terminalAtEnd) {
      if(entryPort==null
          ||(seg.getCoordinateN(0).distance(entryPort)>=1e-5&&seg.getCoordinateN(1).distance(entryPort)>=1e-5))return false;
      Geometry envelope=forbidden(building,dn,clearance).getGeometry();
      return BuildingEntry.approachFromVerifiedPort(building.geometry,envelope,
              buildingAreas.computeIfAbsent(building.geometry,BuildingEntry.Area::new),
              buildingAreas.computeIfAbsent(envelope,BuildingEntry.Area::new),seg,entryPort);
    }

    List<Object> key=List.of(building,new LineCalculationKey(seg,allowedTerminal.x,allowedTerminal.y,
            x(entryPort),y(entryPort),clearance));
    return buildingLeads.computeIfAbsent(key,ignored->{
      Coordinate outside=terminalAtStart?seg.getCoordinateN(1):seg.getCoordinateN(0);
      if(entryPort!=null&&outside.distance(entryPort)>=1e-5)return false;
      // No bend exists inside the building: this whole lead is one straight
      // segment. A selected fallback wall is legal only when the router has
      // reached that wall-distance tier after all closer tiers failed.
      return entryPort==null
              ?BuildingEntry.nearestLead(building.geometry,buildingFacades.get(building),
                  buildingDistances.get(building),buildingAreas.computeIfAbsent(building.geometry,BuildingEntry.Area::new),seg,allowedTerminal)
              :BuildingEntry.straightLead(building.geometry,buildingFacades.get(building),
                  buildingAreas.computeIfAbsent(building.geometry,BuildingEntry.Area::new),seg,allowedTerminal);
    });
  }

  private static double buildingClearance(int dn){return dn<500?5:dn<=800?7:9;}
  private double crossingAngle(LineString route,Geometry obstacle,Coordinate crossing){
    Coordinate[] routeCoordinates=route.getCoordinates();Coordinate ra=null,rb=null;double routeDistance=Double.POSITIVE_INFINITY;
    for(int i=0;i+1<routeCoordinates.length;i++){Coordinate a=routeCoordinates[i],b=routeCoordinates[i+1];double distance=new LineSegment(a,b).distance(crossing);if(distance<routeDistance){routeDistance=distance;ra=a;rb=b;}}
    Coordinate[] best=new Coordinate[2];double bestDistance=Double.POSITIVE_INFINITY;
    for(LineSegment segment:crossingSegments.computeIfAbsent(obstacle,this::crossingSegments)) {
      double distance=segment.distance(crossing);
      if(distance<bestDistance){bestDistance=distance;best[0]=segment.p0;best[1]=segment.p1;}
    }
    if(ra==null||best[0]==null)return 90;
    double ux=rb.x-ra.x,uy=rb.y-ra.y,vx=best[1].x-best[0].x,vy=best[1].y-best[0].y;double den=Math.hypot(ux,uy)*Math.hypot(vx,vy);if(den<1e-9)return 90;double cos=Math.max(-1,Math.min(1,Math.abs((ux*vx+uy*vy)/den)));return Math.toDegrees(Math.acos(cos));
  }

  private List<LineSegment> crossingSegments(Geometry obstacle) {
    List<LineSegment> segments=new ArrayList<>();
    obstacle.getBoundary().apply((org.locationtech.jts.geom.GeometryComponentFilter)component->{
      if(component instanceof LineString)addSegments(segments,(LineString)component);
    });
    if(segments.isEmpty()&&obstacle instanceof LineString)addSegments(segments,(LineString)obstacle);
    return segments;
  }

  private static void addSegments(List<LineSegment> target,LineString line) {
    Coordinate[] coordinates=line.getCoordinates();
    for(int i=1;i<coordinates.length;i++)target.add(new LineSegment(coordinates[i-1],coordinates[i]));
  }
}

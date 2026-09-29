package ru.lct.teplokontur.routing;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.index.strtree.STRtree;

/** Nearest exterior facade and the single approach leg at an indoor endpoint. */
public final class BuildingEntry {
    private static final double EPS=1e-5;
    private BuildingEntry() { }

    public static Geometry exteriorBoundary(Geometry building) {
        List<Geometry> rings=new ArrayList<>();
        collectExteriors(building,rings);
        return building.getFactory().buildGeometry(rings);
    }

    private static void collectExteriors(Geometry geometry,List<Geometry> rings) {
        if(geometry instanceof Polygon)rings.add(((Polygon)geometry).getExteriorRing());
        else if(geometry instanceof GeometryCollection)
            for(int i=0;i<geometry.getNumGeometries();i++)collectExteriors(geometry.getGeometryN(i),rings);
    }

    public static boolean nearestLead(Geometry building,LineString segment,Coordinate terminal) {
        Geometry facade=exteriorBoundary(building);
        return nearestLead(building,facade,new IndexedFacetDistance(facade),segment,terminal);
    }

    public static boolean nearestLead(Geometry building,Geometry facade,
                                      IndexedFacetDistance facadeDistance,LineString segment,Coordinate terminal) {
        return nearestLead(building,facade,facadeDistance,new Area(building),segment,terminal);
    }

    static boolean nearestLead(Geometry building,Geometry facade,IndexedFacetDistance facadeDistance,
                               Area area,LineString segment,Coordinate terminal) {
        if(!straightLead(building,facade,area,segment,terminal))return false;
        double nearest=facadeDistance.distance(building.getFactory().createPoint(terminal));
        return Math.abs(area.lengthInside(segment)-nearest)<=EPS;
    }

    /**
     * Validates a straight terminal lead through the selected exterior wall.
     * A selected fallback need not be perpendicular or globally nearest.
     */
    public static boolean straightLead(Geometry building,LineString segment,Coordinate terminal) {
        return straightLead(building,exteriorBoundary(building),segment,terminal);
    }

    public static boolean straightLead(Geometry building,Geometry facade,
                                       LineString segment,Coordinate terminal) {
        return straightLead(building,facade,new Area(building),segment,terminal);
    }

    static boolean straightLead(Geometry building,Geometry facade,Area area,
                                LineString segment,Coordinate terminal) {
        if(terminal==null||segment.getNumPoints()!=2)return false;
        Coordinate a=segment.getCoordinateN(0),b=segment.getCoordinateN(1);
        Coordinate outside;
        if(a.distance(terminal)<EPS)outside=b;
        else if(b.distance(terminal)<EPS)outside=a;
        else return false;
        GeometryFactory gf=building.getFactory();
        if(!area.covers(terminal)||area.covers(outside))return false;
        LineString lead=gf.createLineString(new Coordinate[]{terminal,outside});
        List<double[]> inside=area.intervals(lead);
        if(inside.size()!=1||inside.get(0)[0]>EPS)return false;
        Coordinate entry=new LengthIndexedLine(lead).extractPoint(inside.get(0)[1]);
        // Any straight lead through the selected exterior is allowed; no perpendicular rule.
        return facade.distance(gf.createPoint(entry))<=EPS;
    }

    private static boolean closestPointOfSomeExteriorWall(Geometry facade,Coordinate entry,Coordinate terminal) {
        final boolean[] result={false};
        facade.apply((GeometryComponentFilter)component->{
            if(result[0]||!(component instanceof LineString))return;
            Coordinate[] ring=component.getCoordinates();
            for(int i=1;i<ring.length;i++) {
                LineSegment wall=new LineSegment(ring[i-1],ring[i]);
                if(wall.distance(entry)>EPS)continue;
                if(wall.closestPoint(terminal).distance(entry)<=EPS){result[0]=true;return;}
            }
        });
        return result[0];
    }

    public static boolean approach(Geometry building,LineString segment,Coordinate port,
                                   Coordinate terminal,double clearance) {
        Geometry facade=exteriorBoundary(building);
        return approach(building,facade,new IndexedFacetDistance(facade),building.buffer(clearance),segment,port,terminal);
    }

    public static boolean approach(Geometry building,Geometry facade,IndexedFacetDistance facadeDistance,
                                   Geometry envelope,LineString segment,Coordinate port,Coordinate terminal) {
        if(port==null||terminal==null)return false;
        GeometryFactory gf=building.getFactory();
        if(!nearestLead(building,facade,facadeDistance,gf.createLineString(new Coordinate[]{port,terminal}),terminal))return false;
        return approachFromVerifiedPort(building,envelope,segment,port);
    }

    /** The caller has already verified the unchanged port-to-terminal lead. */
    static boolean approachFromVerifiedPort(Geometry building,Geometry envelope,LineString segment,Coordinate port) {
        return approachFromVerifiedPort(building,envelope,new Area(building),new Area(envelope),segment,port);
    }

    static boolean approachFromVerifiedPort(Geometry building,Geometry envelope,Area buildingArea,
                                            Area envelopeArea,LineString segment,Coordinate port) {
        GeometryFactory gf=building.getFactory();
        Coordinate a=segment.getCoordinateN(0),b=segment.getCoordinateN(segment.getNumPoints()-1);
        Coordinate outside;
        if(a.distance(port)<EPS)outside=b;
        else if(b.distance(port)<EPS)outside=a;
        else return false;
        LineString outward=gf.createLineString(new Coordinate[]{port,outside});
        if(outward.getLength()<EPS||buildingArea.lengthInside(outward)>EPS)return false;
        List<double[]> intervals=envelopeArea.intervals(outward);
        if(intervals.isEmpty())return true;
        intervals.sort(Comparator.comparingDouble(span->span[0]));
        double lo=intervals.get(0)[0],hi=intervals.get(0)[1];
        if(lo>EPS)return false;
        for(int i=1;i<intervals.size();i++) {
            if(intervals.get(i)[0]>hi+EPS)return false;
            hi=Math.max(hi,intervals.get(i)[1]);
        }
        return hi<outward.getLength()-EPS||envelope.getBoundary().distance(gf.createPoint(outside))<=EPS;
    }

    /** Clips a straight segment against original polygon rings without constructing an overlay. */
    public static final class Area {
        private final STRtree boundary=new STRtree();
        private final IndexedPointInAreaLocator locator;
        public Area(Geometry geometry) {
            locator=new IndexedPointInAreaLocator(geometry);
            geometry.getBoundary().apply((GeometryComponentFilter)component->{
                if(!(component instanceof LineString))return;
                Coordinate[] points=component.getCoordinates();
                for(int i=1;i<points.length;i++)boundary.insert(new Envelope(points[i-1],points[i]),new LineSegment(points[i-1],points[i]));
            });
            boundary.build();
        }
        public double lengthInside(LineString line) {
            double total=0;for(double[] span:intervals(line))total+=span[1]-span[0];return total;
        }
        public boolean covers(Coordinate point){return locator.locate(point)!=Location.EXTERIOR;}
        public List<double[]> intervals(LineString line) {
            if(line.getNumPoints()!=2)throw new IllegalArgumentException("Building lead must be straight");
            LineSegment segment=new LineSegment(line.getCoordinateN(0),line.getCoordinateN(1));
            double length=segment.getLength();
            if(length==0)return locator.locate(segment.p0)==Location.EXTERIOR?Collections.emptyList():Collections.singletonList(new double[]{0,0});
            TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.);cuts.add(length);
            RobustLineIntersector intersection=new RobustLineIntersector();
            for(Object value:boundary.query(line.getEnvelopeInternal())) {
                LineSegment wall=(LineSegment)value;
                intersection.computeIntersection(segment.p0,segment.p1,wall.p0,wall.p1);
                for(int i=0;i<intersection.getIntersectionNum();i++)
                    cuts.add(Math.max(0,Math.min(length,segment.projectionFactor(intersection.getIntersection(i))*length)));
            }
            List<double[]> result=new ArrayList<>();
            double previous=0;
            for(double cut:cuts) {
                if(cut>previous&&locator.locate(segment.pointAlong((previous+cut)/(2*length)))!=Location.EXTERIOR)
                    append(result,previous,cut);
                if(locator.locate(length==0?segment.p0:segment.pointAlong(cut/length))!=Location.EXTERIOR)
                    append(result,cut,cut);
                previous=cut;
            }
            return result;
        }
        private static void append(List<double[]> result,double a,double b) {
            if(!result.isEmpty()&&a<=result.get(result.size()-1)[1]+EPS)
                result.get(result.size()-1)[1]=Math.max(b,result.get(result.size()-1)[1]);
            else result.add(new double[]{a,b});
        }
    }

    private static void collectIntervals(Geometry geometry,LengthIndexedLine line,List<double[]> intervals) {
        if(geometry.isEmpty())return;
        if(geometry instanceof GeometryCollection){for(int i=0;i<geometry.getNumGeometries();i++)collectIntervals(geometry.getGeometryN(i),line,intervals);return;}
        Coordinate[] c=geometry.getCoordinates();double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(Coordinate point:c){double at=line.project(point);lo=Math.min(lo,at);hi=Math.max(hi,at);}
        intervals.add(new double[]{lo,hi});
    }
}

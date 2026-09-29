package ru.lct.teplokontur.routing;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

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
        if(terminal==null||segment.getNumPoints()!=2)return false;
        GeometryFactory gf=building.getFactory();
        if(!building.covers(gf.createPoint(terminal)))return false;
        Coordinate a=segment.getCoordinateN(0),b=segment.getCoordinateN(1);
        Coordinate outside;
        if(a.distance(terminal)<EPS)outside=b;
        else if(b.distance(terminal)<EPS)outside=a;
        else return false;
        if(building.covers(gf.createPoint(outside)))return false;
        Geometry inside=segment.intersection(building);
        if(inside.isEmpty()||inside.getNumGeometries()!=1)return false;
        Coordinate[] lead=inside.getCoordinates();
        Coordinate entry;
        if(lead[0].distance(terminal)<EPS)entry=lead[lead.length-1];
        else if(lead[lead.length-1].distance(terminal)<EPS)entry=lead[0];
        else return false;
        Geometry facade=exteriorBoundary(building);
        double nearest=facade.distance(gf.createPoint(terminal));
        return facade.distance(gf.createPoint(entry))<=EPS
                &&Math.abs(inside.getLength()-nearest)<=EPS;
    }

    public static boolean approach(Geometry building,LineString segment,Coordinate port,
                                   Coordinate terminal,double clearance) {
        if(port==null||terminal==null)return false;
        GeometryFactory gf=building.getFactory();
        if(!nearestLead(building,gf.createLineString(new Coordinate[]{port,terminal}),terminal))return false;
        Coordinate a=segment.getCoordinateN(0),b=segment.getCoordinateN(segment.getNumPoints()-1);
        Coordinate outside;
        if(a.distance(port)<EPS)outside=b;
        else if(b.distance(port)<EPS)outside=a;
        else return false;
        LineString outward=gf.createLineString(new Coordinate[]{port,outside});
        if(outward.getLength()<EPS||outward.intersection(building).getLength()>EPS)return false;
        Geometry envelope=building.buffer(clearance);
        List<double[]> intervals=new ArrayList<>();
        collectIntervals(outward.intersection(envelope),new LengthIndexedLine(outward),intervals);
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

    private static void collectIntervals(Geometry geometry,LengthIndexedLine line,List<double[]> intervals) {
        if(geometry.isEmpty())return;
        if(geometry instanceof GeometryCollection){for(int i=0;i<geometry.getNumGeometries();i++)collectIntervals(geometry.getGeometryN(i),line,intervals);return;}
        Coordinate[] c=geometry.getCoordinates();double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(Coordinate point:c){double at=line.project(point);lo=Math.min(lo,at);hi=Math.max(hi,at);}
        intervals.add(new double[]{lo,hi});
    }
}

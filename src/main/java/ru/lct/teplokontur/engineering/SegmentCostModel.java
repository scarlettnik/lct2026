package ru.lct.teplokontur.engineering;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.teplokontur.domain.*;
import java.util.*;

/** Piecewise linear depth profiles; lengths remain horizontal UTM lengths. */
public class SegmentCostModel {
    private static final class Interval {
        final double a,b,factor,below,minimum;
        double depth;
        Interval(double a,double b,double factor,double depth,double below,double minimum){
            this.a=a;this.b=b;this.factor=factor;this.depth=depth;this.below=below;this.minimum=minimum;
        }
        double at(double x) {
            double distance=Math.max(0,Math.max(a-x,x-b));
            return depth<3?Math.min(3,depth+.1*distance):Math.max(3,depth-.1*distance);
        }
    }

    public List<CostedRouteSegment> splitAndCost(InputSnapshot s,PlanEdge edge,RunMode mode) {
        LineString line=edge.geometry;double length=line.getLength();LengthIndexedLine li=new LengthIndexedLine(line);
        List<Interval> intervals=new ArrayList<>();
        for(InputSnapshot.Restriction r:s.restrictions) {
            RestrictionRule rule=RestrictionRules.get(r.type);
            if(rule==null||!rule.crossingAllowed||!line.getEnvelopeInternal().intersects(r.geometry.getEnvelopeInternal()))continue;
            Geometry intersection=line.intersection(r.geometry);
            for(int k=0;k<intersection.getNumGeometries();k++) {
                Geometry part=intersection.getGeometryN(k);if(part.isEmpty())continue;
                double a=Double.POSITIVE_INFINITY,b=Double.NEGATIVE_INFINITY;
                for(Coordinate c:part.getCoordinates()){double x=li.project(c);a=Math.min(a,x);b=Math.max(b,x);}
                double depth=3,below=Double.NaN;
                double minimum="road".equals(r.type)?1:"tram_tracks".equals(r.type)?1.2:.7;
                // Road/tram depths are lower bounds, not targets for an ascent.
                // Keeping the normal depth avoids unnecessary ramps near nodes.
                boolean utility="gas_pipeline".equals(r.type)||"power_cable".equals(r.type);
                if(utility){a=b=(a+b)/2; if(mode==RunMode.DEPTH){double top="gas_pipeline".equals(r.type)?2.8:2.7;
                    double height="gas_pipeline".equals(r.type)?.4:.2;
                    below=top+height+rule.verticalClearance;
                    depth=crossingDepth(top,height,rule.verticalClearance,edge.dn,a,length);}}
                intervals.add(new Interval(Math.max(0,a-rule.extraEachSide),Math.min(length,b+rule.extraEachSide),rule.specialFactor,depth,below,minimum));
            }
        }
        for(InputSnapshot.ExistingEdge ex:s.network) {
            if(!line.getEnvelopeInternal().intersects(ex.geometry.getEnvelopeInternal()))continue;
            Geometry intersection=line.intersection(ex.geometry);
            if(intersection.isEmpty()||intersection.getDimension()>=1)continue;
            for(Coordinate c:intersection.getCoordinates()) {
                double x=li.project(c);if(x<.25||x>length-.25)continue;
                double depth=mode==RunMode.DEPTH?crossingDepth(3,RuleBook.byDn(ex.dn).pairHeight,.5,edge.dn,x,length):3;
                intervals.add(new Interval(Math.max(0,x-2),Math.min(length,x+2),1.05,depth,
                        3+RuleBook.byDn(ex.dn).pairHeight+.5,.7));
            }
        }
        if(mode==RunMode.DEPTH)reconcileDepths(intervals);
        SortedSet<Double> breaks=new TreeSet<>();breaks.add(0.);breaks.add(length);
        for(Interval in:intervals) {
            breaks.add(in.a);breaks.add(in.b);
            if(mode==RunMode.DEPTH){double ramp=Math.abs(in.depth-3)/.1;breaks.add(Math.max(0,in.a-ramp));breaks.add(Math.min(length,in.b+ramp));}
        }
        // Envelope intersections are real slope changes, even between two ramps.
        List<Double> initial=new ArrayList<>(breaks);
        if(mode==RunMode.DEPTH)for(int k=0;k+1<initial.size();k++) {
            double a=initial.get(k),b=initial.get(k+1);
            for(int i=0;i<intervals.size();i++)for(int j=i+1;j<intervals.size();j++) {
                Interval x=intervals.get(i),y=intervals.get(j);
                double da=x.at(a)-y.at(a),db=x.at(b)-y.at(b);
                if(da*db<0)breaks.add(a+(b-a)*da/(da-db));
            }
        }
        List<Double> points=new ArrayList<>(breaks);List<CostedRouteSegment> out=new ArrayList<>();
        for(int i=0;i+1<points.size();i++) {
            double a=points.get(i),b=points.get(i+1);if(b-a<1e-7)continue;
            CostedRouteSegment segment=new CostedRouteSegment();
            segment.geometry=(LineString)li.extractLine(a,b);segment.length=segment.geometry.getLength();
            segment.dn=edge.dn;segment.flow=edge.flow;segment.specialFactor=1;
            for(Interval in:intervals)if((a+b)/2>=in.a&&(a+b)/2<=in.b)segment.specialFactor=Math.max(segment.specialFactor,in.factor);
            segment.layingMethod=segment.specialFactor>1?"special":"base";
            double da=mode==RunMode.DEPTH?depthAt(a,intervals):3,db=mode==RunMode.DEPTH?depthAt(b,intervals):3;
            segment.depthStart=mode==RunMode.DEPTH?da:Double.NaN;segment.depthEnd=mode==RunMode.DEPTH?db:Double.NaN;
            segment.cost=segment.length*RuleBook.byDn(edge.dn).newCost*segment.specialFactor*(RuleBook.depthFactor(da)+RuleBook.depthFactor(db))/2;
            out.add(segment);
        }
        return out;
    }

    private double crossingDepth(double top,double height,double gap,int dn,double chainage,double length) {
        double above=top-gap-RuleBook.byDn(dn).pairHeight;
        double below=top+height+gap;
        // Both options use a four metre plateau and return to depth 3 at each node.
        if(above>=.7&&Math.min(chainage,length-chainage)>=2+(3-above)/.1)return above;
        return below;
    }

    /** Agree on plateaus before combining ramps; mixed envelopes can be discontinuous. */
    private void reconcileDepths(List<Interval> intervals) {
        boolean changed=true;
        while(changed) {
            changed=false;
            for(Interval x:intervals)for(Interval y:intervals) {
                if(x==y)continue;
                if(x.depth<3) {
                    double separation=Math.max(0,Math.max(x.a-y.b,y.a-x.b));
                    boolean mixed=y.depth>3&&separation<(3-x.depth+y.depth-3)/.1-1e-8;
                    double closest=Math.max(y.a,Math.min(y.b,(x.a+x.b)/2));
                    boolean tooShallow=Double.isNaN(y.below)&&x.at(closest)<y.minimum-1e-8;
                    if(mixed||tooShallow){x.depth=x.below;changed=true;continue;}
                }
                if(Double.isNaN(x.below))continue; // Roads impose a minimum, not a plateau.
                double a=y.at(x.a),b=y.at(x.b);
                double required=x.depth<3?Math.min(a,b):Math.max(a,b);
                if(x.depth<3&&required<x.depth-1e-8||x.depth>3&&required>x.depth+1e-8) {
                    x.depth=required;changed=true;
                }
            }
        }
    }

    /** Routing must reject profiles that cannot return to normal depth at the two nodes. */
    public boolean feasibleDepth(InputSnapshot snapshot,LineString line,int dn) {
        PlanEdge edge=new PlanEdge("profile",null,null,line);edge.dn=dn;
        return feasibleDepth(splitAndCost(snapshot,edge,RunMode.DEPTH));
    }

    public boolean feasibleDepth(List<CostedRouteSegment> parts) {
        if(parts.isEmpty()||Math.abs(parts.get(0).depthStart-3)>1e-6
                ||Math.abs(parts.get(parts.size()-1).depthEnd-3)>1e-6)return false;
        for(CostedRouteSegment part:parts)
            if(!Double.isFinite(part.depthStart)||!Double.isFinite(part.depthEnd)
                    ||Math.min(part.depthStart,part.depthEnd)<.7-1e-8
                    ||Math.abs(part.depthEnd-part.depthStart)>part.length*.100001)return false;
        return true;
    }

    private double depthAt(double x,List<Interval> intervals) {
        double shallow=3,deep=3;
        for(Interval interval:intervals){double d=interval.at(x);shallow=Math.min(shallow,d);deep=Math.max(deep,d);}
        return deep>3+1e-9?deep:shallow;
    }
}

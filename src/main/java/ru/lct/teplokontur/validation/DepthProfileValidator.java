package ru.lct.teplokontur.validation;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.SegmentCostModel;

/** Independently checks slopes, normal node depth, crossing plateaus and clearance. */
final class DepthProfileValidator {
    void validate(InputSnapshot snapshot,NetworkPlan plan,ValidationReport report) {
        if(plan.mode!=RunMode.DEPTH)return;
        for(PlanEdge edge:plan.edges) {
            List<CostedRouteSegment> segments=new SegmentCostModel().splitAndCost(snapshot,edge,plan.mode);
            if(segments.isEmpty()){report.error("Empty depth profile: "+edge.id);continue;}
            if(Math.abs(segments.get(0).depthStart-3)>1e-6||Math.abs(segments.get(segments.size()-1).depthEnd-3)>1e-6)
                report.error("Depth profile does not return to normal node depth: "+edge.id);
            double last=segments.get(0).depthStart;
            for(CostedRouteSegment part:segments) {
                if(!Double.isFinite(part.depthStart)||!Double.isFinite(part.depthEnd)||Math.min(part.depthStart,part.depthEnd)<.7-1e-8)
                    report.error("Invalid depth: "+edge.id);
                if(Math.abs(part.depthStart-last)>1e-6)report.error("Depth discontinuity: "+edge.id);
                if(Math.abs(part.depthEnd-part.depthStart)>part.length*.100001)report.error("Depth slope >0.10: "+edge.id);
                last=part.depthEnd;
            }
            for(InputSnapshot.Restriction restriction:snapshot.restrictions) {
                if("road".equals(restriction.type)||"tram_tracks".equals(restriction.type)||"railway".equals(restriction.type)) {
                    double minimum="road".equals(restriction.type)?1:1.2;
                    for(CostedRouteSegment part:segments)if(part.geometry.intersects(restriction.geometry)&&Math.min(part.depthStart,part.depthEnd)<minimum-1e-8)
                        report.error("Insufficient depth under road or tracks: "+edge.id);
                }
                if("gas_pipeline".equals(restriction.type))checkCrossings(edge,restriction.geometry,2.8,.4,.2,segments,report);
                if("power_cable".equals(restriction.type))checkCrossings(edge,restriction.geometry,2.7,.2,.5,segments,report);
            }
            for(InputSnapshot.ExistingEdge existing:snapshot.network)
                checkCrossings(edge,existing.geometry,3,RuleBook.byDn(existing.dn).pairHeight,.5,segments,report);
        }
    }

    private void checkCrossings(PlanEdge edge,Geometry obstacle,double top,double height,double gap,
                                List<CostedRouteSegment> segments,ValidationReport report) {
        if(!edge.geometry.getEnvelopeInternal().intersects(obstacle.getEnvelopeInternal()))return;
        Geometry intersection=edge.geometry.intersection(obstacle);
        LengthIndexedLine indexed=new LengthIndexedLine(edge.geometry);
        for(Coordinate coordinate:intersection.getCoordinates()) {
            double x=indexed.project(coordinate),length=edge.geometry.getLength();
            if(x<.25||x>length-.25)continue;
            if(x<2||x>length-2){report.error("Crossing lacks 4m plateau: "+edge.id);continue;}
            double depth=at(segments,x);
            boolean above=depth+RuleBook.byDn(edge.dn).pairHeight<=top-gap+1e-6;
            boolean below=depth>=top+height+gap-1e-6;
            if(!above&&!below)report.error("Vertical crossing clearance: "+edge.id);
            // Check all profile vertices within the plateau, including both ends.
            if(Math.abs(at(segments,x-2)-depth)>1e-6||Math.abs(at(segments,x+2)-depth)>1e-6)
                report.error("Crossing plateau changes depth: "+edge.id);
            double position=0;
            for(CostedRouteSegment part:segments){position+=part.length;if(position>x-2&&position<x+2&&Math.abs(part.depthEnd-depth)>1e-6)report.error("Crossing plateau changes depth: "+edge.id);}
        }
    }

    private double at(List<CostedRouteSegment> segments,double x) {
        double position=0;
        for(CostedRouteSegment part:segments){if(x<=position+part.length+1e-8)return part.depthStart+(part.depthEnd-part.depthStart)*Math.max(0,Math.min(1,(x-position)/part.length));position+=part.length;}
        return segments.get(segments.size()-1).depthEnd;
    }
}

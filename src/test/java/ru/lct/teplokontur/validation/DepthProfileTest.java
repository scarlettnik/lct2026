package ru.lct.teplokontur.validation;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.SegmentCostModel;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DepthProfileTest {
    private final GeometryFactory gf=new GeometryFactory();

    @Test void crossingHasFourMetrePlateauAndSeparateRamps() {
        InputSnapshot snapshot=new InputSnapshot(1);
        restriction(snapshot,"power_cable",50);
        PlanEdge edge=edge();
        List<CostedRouteSegment> profile=new SegmentCostModel().splitAndCost(snapshot,edge,RunMode.DEPTH);
        assertTrue(profile.size()>=5);
        CostedRouteSegment plateau=profile.stream().filter(p->p.layingMethod.equals("special")).findFirst().orElseThrow();
        assertEquals(4,plateau.length,1e-8);
        assertEquals(plateau.depthStart,plateau.depthEnd,1e-8);
        assertTrue(plateau.depthStart+RuleBook.byDn(edge.dn).pairHeight<=2.7-.5+1e-8);
        assertEquals(3,profile.get(0).depthStart,1e-8);
        assertEquals(3,profile.get(profile.size()-1).depthEnd,1e-8);
        for(CostedRouteSegment segment:profile)assertTrue(Math.abs(segment.depthEnd-segment.depthStart)<=segment.length*.100001);
    }

    @Test void nearbyCrossingsPreserveEnvelopeSlopeChange() {
        InputSnapshot snapshot=new InputSnapshot(2);
        restriction(snapshot,"power_cable",40);restriction(snapshot,"power_cable",60);
        PlanEdge edge=edge();
        List<CostedRouteSegment> profile=new SegmentCostModel().splitAndCost(snapshot,edge,RunMode.DEPTH);
        assertTrue(profile.stream().anyMatch(p->Math.abs(p.geometry.getCoordinateN(p.geometry.getNumPoints()-1).x-50)<1e-8));
        NetworkPlan plan=new NetworkPlan();plan.mode=RunMode.DEPTH;plan.edges.add(edge);
        ValidationReport report=new ValidationReport();new DepthProfileValidator().validate(snapshot,plan,report);
        assertTrue(report.errors.isEmpty(),report.errors.toString());
    }

    private void restriction(InputSnapshot s,String type,double x){InputSnapshot.Restriction r=new InputSnapshot.Restriction();r.id=type+x;r.type=type;r.geometry=gf.createLineString(new Coordinate[]{new Coordinate(x,-20),new Coordinate(x,20)});s.restrictions.add(r);}
    private PlanEdge edge(){PlanNode a=new PlanNode("a",NodeKind.TIE_IN,gf.createPoint(new Coordinate(0,0))),b=new PlanNode("b",NodeKind.TERMINAL,gf.createPoint(new Coordinate(100,0)));PlanEdge e=new PlanEdge("edge",a,b,gf.createLineString(new Coordinate[]{a.point.getCoordinate(),b.point.getCoordinate()}));e.dn=125;e.flow=20;return e;}
}

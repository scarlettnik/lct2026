package ru.lct.teplokontur.engineering;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.lct.teplokontur.domain.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionEvaluatorTest {
    private final GeometryFactory gf=new GeometryFactory();

    @Test void aggregatesSharedUpstreamFlowAndReconstructsOnlyAffectedParts() {
        for(boolean reverse:new boolean[]{false,true}) {
            InputSnapshot s=new InputSnapshot(1);s.source=point(0,0);
            InputSnapshot.ExistingEdge existing=new InputSnapshot.ExistingEdge();existing.id="pipe";existing.upstream="source";
            existing.dn=80;existing.flow=5;
            existing.geometry=gf.createLineString(new Coordinate[]{new Coordinate(reverse?100:0,0),new Coordinate(reverse?0:100,0)});
            s.network.add(existing);
            NetworkPlan plan=new NetworkPlan();
            root(plan,existing,30,10,reverse);root(plan,existing,70,10,reverse);
            ReconstructionEvaluator.Result result=new ReconstructionEvaluator().evaluate(s,plan);
            assertEquals(70,result.length,1e-8);
            assertEquals(30*RuleBook.byDn(125).reconCost+40*RuleBook.byDn(100).reconCost,result.cost,1e-6);
            assertTrue(result.lines.stream().allMatch(line->line.geometry.getEnvelopeInternal().getMaxX()<=70+1e-8));
        }
    }

    private void root(NetworkPlan plan,InputSnapshot.ExistingEdge existing,double x,double flow,boolean reverse) {
        PlanNode root=new PlanNode("tie"+x,NodeKind.TIE_IN,point(x,0));root.existingObjectId=existing.id;
        root.existingObjectType="heat_network";root.tieChainage=reverse?100-x:x;
        PlanNode end=new PlanNode("leaf"+x,NodeKind.TERMINAL,point(x,20));
        PlanEdge edge=new PlanEdge("branch"+x,root,end,gf.createLineString(new Coordinate[]{root.point.getCoordinate(),end.point.getCoordinate()}));edge.flow=flow;edge.dn=80;root.children.add(edge);plan.roots.add(root);
    }
    private Point point(double x,double y){return gf.createPoint(new Coordinate(x,y));}
}

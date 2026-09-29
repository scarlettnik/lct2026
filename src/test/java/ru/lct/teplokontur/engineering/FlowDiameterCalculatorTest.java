package ru.lct.teplokontur.engineering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.domain.NodeKind;
import ru.lct.teplokontur.domain.PlanEdge;
import ru.lct.teplokontur.domain.PlanNode;
import ru.lct.teplokontur.domain.RunMode;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.validation.EngineeringValidator;

class FlowDiameterCalculatorTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void promotesSameDiameterRunUsingRouteGeometryBeforeEvaluationSetsCachedLength() {
        NetworkPlan plan = new NetworkPlan();
        PlanNode root = new PlanNode("root", NodeKind.TIE_IN, point(0));
        PlanNode terminal = new PlanNode("terminal", NodeKind.TERMINAL, point(500));
        terminal.oksId = "oks";
        PlanEdge edge = new PlanEdge("edge", root, terminal, geometryFactory.createLineString(
                new Coordinate[] {root.point.getCoordinate(), terminal.point.getCoordinate()}));
        root.children.add(edge);
        terminal.parentEdge = edge;
        plan.roots.add(root);
        plan.edges.add(edge);

        new FlowDiameterCalculator().apply(plan, Collections.singletonMap("oks", 20.0));

        assertEquals(125, edge.dn);
    }

    private org.locationtech.jts.geom.Point point(double x) {
        return geometryFactory.createPoint(new Coordinate(x, 0));
    }

    @Test
    void sizesContinuousRunAcrossCamerasWithoutResettingTheLengthLimit() {
        NetworkPlan plan = new NetworkPlan();
        plan.mode = RunMode.TWO_D;
        PlanNode root = new PlanNode("root", NodeKind.TIE_IN, point(0));
        PlanNode first = new PlanNode("first", NodeKind.CHAMBER, point(300));
        PlanNode second = new PlanNode("second", NodeKind.CHAMBER, point(600));
        PlanNode terminal = new PlanNode("terminal", NodeKind.TERMINAL, point(900));
        terminal.oksId = "oks";
        PlanEdge firstEdge = edge("first", root, first);
        PlanEdge secondEdge = edge("second", first, second);
        PlanEdge thirdEdge = edge("third", second, terminal);
        plan.roots.add(root);
        plan.nodes.put(root.id, root);
        plan.nodes.put(first.id, first);
        plan.nodes.put(second.id, second);
        plan.nodes.put(terminal.id, terminal);
        Collections.addAll(plan.edges, firstEdge, secondEdge, thirdEdge);

        new FlowDiameterCalculator().apply(plan, Collections.singletonMap("oks", 20.0));

        assertEquals(200, firstEdge.dn);
        assertEquals(200, secondEdge.dn);
        assertEquals(200, thirdEdge.dn);
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Terminal input = new InputSnapshot.Terminal();
        input.oksId = "oks";
        input.flow = 20.0;
        input.point = terminal.point;
        snapshot.terminals.add(input);
        assertEquals(true, new EngineeringValidator().validate(snapshot, plan).passed);
    }

    private PlanEdge edge(String id, PlanNode parent, PlanNode child) {
        PlanEdge edge = new PlanEdge(id, parent, child, geometryFactory.createLineString(
                new Coordinate[] {parent.point.getCoordinate(), child.point.getCoordinate()}));
        parent.children.add(edge);
        child.parentEdge = edge;
        return edge;
    }
}

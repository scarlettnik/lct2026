package ru.lct.teplokontur.engineering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.domain.NodeKind;
import ru.lct.teplokontur.domain.PlanEdge;
import ru.lct.teplokontur.domain.PlanNode;
import ru.lct.teplokontur.domain.RunMode;

/** Participant clarification: pipe tie creates a new chamber; reconstruction is disabled. */
class PlanEvaluatorTieInTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void pipeTieCreatesChamberWithoutTieInOrReconstructionFeatures() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Terminal terminal = new InputSnapshot.Terminal();
        terminal.oksId = "consumer";
        terminal.pointId = "consumer-point";
        terminal.flow = 20.0;
        terminal.point = geometryFactory.createPoint(new Coordinate(100, 0));
        snapshot.terminals.add(terminal);
        InputSnapshot.ExistingEdge existing = new InputSnapshot.ExistingEdge();
        existing.id = "existing"; existing.dn = 80; existing.flow = 10;
        existing.geometry = geometryFactory.createLineString(new Coordinate[]{new Coordinate(-50,0),new Coordinate(0,0)});
        snapshot.network.add(existing);

        NetworkPlan plan = new NetworkPlan();
        plan.mode = RunMode.TWO_D;
        PlanNode tie = new PlanNode("tie", NodeKind.TIE_IN, geometryFactory.createPoint(new Coordinate(0, 0)));
        tie.existingObjectType = "heat_network";
        tie.existingObjectId = existing.id; tie.tieChainage = 50;
        PlanNode consumer = new PlanNode("consumer-point", NodeKind.TERMINAL, terminal.point);
        consumer.oksId = terminal.oksId;
        PlanEdge edge = new PlanEdge("edge", tie, consumer, geometryFactory.createLineString(
                new Coordinate[] {tie.point.getCoordinate(), consumer.point.getCoordinate()}));
        tie.children.add(edge);
        consumer.parentEdge = edge;
        plan.roots.add(tie);
        plan.nodes.put(tie.id, tie);
        plan.nodes.put(consumer.id, consumer);
        plan.edges.add(edge);

        new PlanEvaluator().evaluate(snapshot, plan);

        assertEquals(8_974_800.0, plan.constructionCost, 1e-6);
        assertEquals(3_000_000.0, plan.chamberCost, 1e-6);
        assertEquals(0.0, plan.tieInCost, 1e-6);
        assertEquals(0.0, plan.reconstructionCost, 1e-6);
        assertEquals(0.0, plan.reconstructionLength, 1e-6);
        assertEquals(100, plan.length, 1e-6);
        assertEquals(ru.lct.teplokontur.domain.RuleBook.officialScore(8_974_800+3_000_000,100), plan.score, 1e-9);
    }
}

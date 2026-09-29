package ru.lct.teplokontur.optimization;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.teplokontur.config.CompetitionProperties;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.domain.NodeKind;
import ru.lct.teplokontur.domain.PlanEdge;
import ru.lct.teplokontur.domain.PlanNode;
import ru.lct.teplokontur.domain.RunMode;
import ru.lct.teplokontur.engineering.PlanEvaluator;
import ru.lct.teplokontur.validation.EngineeringValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanLocalSearchTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void replacesAValidDetourWithAShorterStraightSegment() {
        InputSnapshot snapshot = new InputSnapshot(1L);
        InputSnapshot.Terminal terminal = new InputSnapshot.Terminal();
        terminal.oksId = "oks-1";
        terminal.pointId = "entry-1";
        terminal.flow = 10;
        terminal.point = point(10, 0);
        snapshot.terminals.add(terminal);
        InputSnapshot.ExistingEdge existing = new InputSnapshot.ExistingEdge();
        existing.id = "network-1";
        existing.upstream = "source";
        existing.dn = 300;
        existing.geometry = line(-20, 0, 0, 0);
        snapshot.network.add(existing);

        NetworkPlan plan = new NetworkPlan();
        plan.mode = RunMode.TWO_D;
        PlanNode root = new PlanNode("root", NodeKind.TIE_IN, point(0, 0));
        root.existingObjectType = "heat_network";
        root.existingObjectId = existing.id;
        root.tieChainage = existing.geometry.getLength();
        PlanNode leaf = new PlanNode("entry-1", NodeKind.TERMINAL, terminal.point);
        leaf.oksId = terminal.oksId;
        PlanEdge edge = new PlanEdge("edge", root, leaf, line(0, 0, 0, 10, 10, 10, 10, 0));
        edge.flow = terminal.flow;
        edge.dn = 100;
        root.children.add(edge);
        leaf.parentEdge = edge;
        plan.roots.add(root);
        plan.nodes.put(root.id, root);
        plan.nodes.put(leaf.id, leaf);
        plan.edges.add(edge);

        PlanEvaluator evaluator = new PlanEvaluator();
        EngineeringValidator validator = new EngineeringValidator();
        evaluator.evaluate(snapshot, plan);
        plan.validation = validator.validate(snapshot, plan);

        boolean improved = new PlanLocalSearch().improve(snapshot, plan, evaluator, validator);

        assertTrue(improved);
        assertEquals(10.0, plan.edges.get(0).geometry.getLength(), 1e-8);
        assertTrue(plan.validation.passed);
    }

    private org.locationtech.jts.geom.Point point(double x, double y) {
        return geometryFactory.createPoint(new Coordinate(x, y));
    }

    private LineString line(double... coordinates) {
        Coordinate[] points = new Coordinate[coordinates.length / 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Coordinate(coordinates[2 * i], coordinates[2 * i + 1]);
        }
        return geometryFactory.createLineString(points);
    }
}

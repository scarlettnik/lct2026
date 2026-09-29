package ru.lct.teplokontur.optimization;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.domain.NodeKind;
import ru.lct.teplokontur.domain.PlanEdge;
import ru.lct.teplokontur.domain.PlanNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantDiversityTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void treatsMateriallyDifferentCorridorsAsDifferentVariants() {
        NetworkPlan north = plan(new Coordinate[]{new Coordinate(0, 0), new Coordinate(50, 40), new Coordinate(100, 0)});
        NetworkPlan south = plan(new Coordinate[]{new Coordinate(0, 0), new Coordinate(50, -40), new Coordinate(100, 0)});

        VariantDiversity diversity = new VariantDiversity();

        assertTrue(diversity.materiallyDifferent(north, south));
    }

    @Test
    void rejectsAByteForByteDuplicateRouteAsAnotherVariant() {
        Coordinate[] corridor = {new Coordinate(0, 0), new Coordinate(50, 40), new Coordinate(100, 0)};
        VariantDiversity diversity = new VariantDiversity();

        assertFalse(diversity.materiallyDifferent(plan(corridor), plan(corridor)));
    }

    private NetworkPlan plan(Coordinate[] coordinates) {
        Point start = geometryFactory.createPoint(coordinates[0]);
        Point end = geometryFactory.createPoint(coordinates[coordinates.length - 1]);
        PlanNode root = new PlanNode("root", NodeKind.TIE_IN, start);
        root.existingObjectType = "heat_network";
        root.existingObjectId = "existing-1";
        PlanNode terminal = new PlanNode("terminal", NodeKind.TERMINAL, end);
        terminal.oksId = "oks-1";
        LineString geometry = geometryFactory.createLineString(coordinates);
        PlanEdge edge = new PlanEdge("edge", root, terminal, geometry);
        root.children.add(edge);
        terminal.parentEdge = edge;

        NetworkPlan plan = new NetworkPlan();
        plan.roots.add(root);
        plan.nodes.put(root.id, root);
        plan.nodes.put(terminal.id, terminal);
        plan.edges.add(edge);
        return plan;
    }
}

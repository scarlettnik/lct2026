package ru.lct.teplokontur.optimization;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.domain.NodeKind;
import ru.lct.teplokontur.domain.PlanEdge;
import ru.lct.teplokontur.domain.PlanNode;

final class VariantDiversityTestSupport {
    private final GeometryFactory geometryFactory;

    VariantDiversityTestSupport(GeometryFactory geometryFactory) {
        this.geometryFactory = geometryFactory;
    }

    NetworkPlan plan(Coordinate[] coordinates) {
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

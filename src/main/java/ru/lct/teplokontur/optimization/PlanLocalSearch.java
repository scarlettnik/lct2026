package ru.lct.teplokontur.optimization;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.domain.PlanEdge;
import ru.lct.teplokontur.domain.ValidationReport;
import ru.lct.teplokontur.engineering.PlanEvaluator;
import ru.lct.teplokontur.validation.EngineeringValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cheap, topology-preserving improvement pass.
 *
 * <p>The constructive router can leave a legal but unnecessarily bent edge
 * after a previously placed branch has constrained the search corridor. This
 * pass tests the direct segment for each existing edge and keeps it only when
 * the full evaluator and validator both accept an objectively better plan.</p>
 */
public final class PlanLocalSearch {
    private static final double EPSILON = 1e-8;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public boolean improve(InputSnapshot snapshot, NetworkPlan plan, PlanEvaluator evaluator,
                           EngineeringValidator validator) {
        boolean changed = false;
        double bestScore = plan.score;
        List<PlanEdge> edges = new ArrayList<>(plan.edges);
        edges.sort(Comparator.comparingDouble((PlanEdge edge) -> edge.geometry.getLength()).reversed());

        for (PlanEdge edge : edges) {
            LineString direct = geometryFactory.createLineString(new Coordinate[]{
                    edge.parent.point.getCoordinate(), edge.child.point.getCoordinate()});
            if (direct.getLength() >= edge.geometry.getLength() - EPSILON) {
                continue;
            }

            LineString original = edge.geometry;
            ValidationReport originalValidation = plan.validation;
            edge.geometry = direct;
            evaluator.evaluate(snapshot, plan);
            plan.validation = validator.validate(snapshot, plan);
            if (plan.validation.passed && plan.score < bestScore - EPSILON) {
                bestScore = plan.score;
                changed = true;
                continue;
            }

            edge.geometry = original;
            evaluator.evaluate(snapshot, plan);
            plan.validation = originalValidation;
        }
        return changed;
    }
}

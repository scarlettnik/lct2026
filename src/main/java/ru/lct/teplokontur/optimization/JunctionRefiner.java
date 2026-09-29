package ru.lct.teplokontur.optimization;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.PlanEvaluator;
import ru.lct.teplokontur.validation.EngineeringValidator;

/** Refines chamber locations while retaining only fully valid score improvements. */
public final class JunctionRefiner {
    private final GeometryFactory gf = new GeometryFactory();
    private final PlanEvaluator evaluator = new PlanEvaluator();
    private final EngineeringValidator validator = new EngineeringValidator();

    public void improve(InputSnapshot snapshot, NetworkPlan plan) {
        for (double step : new double[]{24,12,6,3,1}) {
            for (int pass=0; pass<3; pass++) {
                boolean changed=false;
                for (String id : new ArrayList<>(plan.nodes.keySet())) {
                    PlanNode node=plan.nodes.get(id);
                    if (node.kind!=NodeKind.CHAMBER) continue;
                    List<PlanEdge> adjacent=new ArrayList<>(node.children);
                    adjacent.add(node.parentEdge);
                    Coordinate origin=node.point.getCoordinate();
                    List<Coordinate> candidates=new ArrayList<>();
                    for(int angle=0;angle<8;angle++) candidates.add(new Coordinate(
                            origin.x+step*Math.cos(angle*Math.PI/4), origin.y+step*Math.sin(angle*Math.PI/4)));
                    candidates.sort(Comparator.comparingDouble(c -> localCost(node,adjacent,c)));
                    double originalCost=localCost(node,adjacent,origin);
                    double originalScore=plan.score;
                    ValidationReport originalValidation=plan.validation;
                    for(Coordinate candidate:candidates) {
                        if(localCost(node,adjacent,candidate)>=originalCost-1e-8) break;
                        List<LineString> geometry=new ArrayList<>();
                        PlanNode replacement=new PlanNode(node.id,node.kind,gf.createPoint(candidate));
                        replacement.parentEdge=node.parentEdge; replacement.children.addAll(node.children);
                        for(PlanEdge edge:adjacent) {
                            geometry.add(edge.geometry);
                            Coordinate[] coords=CoordinateArrays.copyDeep(edge.geometry.getCoordinates());
                            if(edge.parent==node){coords[0]=candidate;edge.parent=replacement;}
                            else{coords[coords.length-1]=candidate;edge.child=replacement;}
                            edge.geometry=gf.createLineString(coords);
                        }
                        plan.nodes.put(id,replacement);
                        evaluator.evaluate(snapshot,plan);
                        if(plan.score<originalScore-1e-8) {
                            plan.validation=validator.validate(snapshot,plan);
                            if(plan.validation.passed){changed=true;break;}
                        }
                        for(int i=0;i<adjacent.size();i++) {
                            PlanEdge edge=adjacent.get(i); edge.geometry=geometry.get(i);
                            if(edge.parent==replacement)edge.parent=node;else edge.child=node;
                        }
                        plan.nodes.put(id,node);
                        evaluator.evaluate(snapshot,plan);
                        plan.validation=originalValidation;
                    }
                }
                if(!changed)break;
            }
        }
    }

    private double localCost(PlanNode node,List<PlanEdge> edges,Coordinate point) {
        double cost=0;
        for(PlanEdge edge:edges) {
            Coordinate other=edge.parent==node?edge.geometry.getCoordinateN(1):edge.geometry.getCoordinateN(edge.geometry.getNumPoints()-2);
            cost+=point.distance(other)*(.003+.7*RuleBook.byDn(edge.dn).newCost/25_000_000.0);
        }
        return cost;
    }
}

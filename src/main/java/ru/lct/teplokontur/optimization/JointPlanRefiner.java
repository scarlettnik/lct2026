package ru.lct.teplokontur.optimization;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.*;
import ru.lct.teplokontur.routing.*;
import ru.lct.teplokontur.validation.EngineeringValidator;

/** Joint discrete optimisation of every chamber and pipe tie in a routed forest. */
public final class JointPlanRefiner {
    private final GeometryFactory gf = new GeometryFactory();
    private final PlanEvaluator evaluator = new PlanEvaluator();
    private final EngineeringValidator validator = new EngineeringValidator();
    private final SegmentCostModel costs = new SegmentCostModel();

    public NetworkPlan improve(InputSnapshot snapshot, NetworkPlan initial) {
        NetworkPlan best = initial;
        RouteConstraintEngine constraints = new RouteConstraintEngine(snapshot);
        for (double step : new double[]{48,24,12,6,3,1}) {
            for (int pass=0; pass<3; pass++) {
                NetworkPlan next = round(snapshot,best,constraints,step);
                if (next == best) break;
                best = next;
            }
        }
        return best;
    }

    private NetworkPlan round(InputSnapshot s, NetworkPlan plan, RouteConstraintEngine constraints, double step) {
        List<PlanNode> nodes = new ArrayList<>(plan.nodes.values());
        Map<PlanNode,Integer> index = new IdentityHashMap<>();
        Map<Integer,Integer> counts = new LinkedHashMap<>();
        List<List<Point>> positions = new ArrayList<>();
        for (int i=0;i<nodes.size();i++) {
            index.put(nodes.get(i),i);
            positions.add(positions(s,nodes.get(i),step));
            counts.put(i,positions.get(i).size());
        }
        int virtualRoot=nodes.size(); counts.put(virtualRoot,1);
        Map<JointChamberMilp.Edge,double[][]> matrices=new LinkedHashMap<>();
        Map<PlanEdge,LineString[][]> routes=new IdentityHashMap<>();
        for (PlanNode root:plan.roots) matrices.put(new JointChamberMilp.Edge(virtualRoot,index.get(root)),new double[1][counts.get(index.get(root))]);
        for (PlanEdge edge:plan.edges) {
            int a=index.get(edge.parent),b=index.get(edge.child);
            double[][] matrix=new double[counts.get(a)][counts.get(b)];
            LineString[][] lines=new LineString[counts.get(a)][counts.get(b)];
            for(int i=0;i<matrix.length;i++) for(int j=0;j<matrix[i].length;j++) {
                Point from=positions.get(a).get(i),to=positions.get(b).get(j);
                Coordinate[] coords=CoordinateArrays.copyDeep(edge.geometry.getCoordinates());
                coords[0]=from.getCoordinate(); coords[coords.length-1]=to.getCoordinate();
                LineString shaped=gf.createLineString(coords);
                LineString direct=gf.createLineString(new Coordinate[]{from.getCoordinate(),to.getCoordinate()});
                double shapedCost=legalCost(s,plan,edge,shaped,constraints);
                double directCost=legalCost(s,plan,edge,direct,constraints);
                lines[i][j]=directCost<shapedCost?direct:shaped;
                matrix[i][j]=Math.min(shapedCost,directCost);
            }
            matrices.put(new JointChamberMilp.Edge(a,b),matrix); routes.put(edge,lines);
        }
        NetworkPlan best=plan;
        for(JointChamberMilp.Assignment assignment:new JointChamberMilp().solve(virtualRoot,counts,matrices,24)) {
            NetworkPlan candidate=SharedNetworkBuilder.copy(plan);
            for(int i=0;i<nodes.size();i++) {
                PlanNode old=candidate.nodes.get(nodes.get(i).id);
                Point position=positions.get(i).get(assignment.selected().get(i));
                PlanNode moved=new PlanNode(old.id,old.kind,position);
                moved.oksId=old.oksId; moved.existingObjectId=old.existingObjectId;
                moved.existingObjectType=old.existingObjectType; moved.existingDn=old.existingDn; moved.tieChainage=old.tieChainage;
                if(old.kind==NodeKind.TIE_IN&&"heat_network".equals(old.existingObjectType))
                    for(InputSnapshot.ExistingEdge ex:s.network) if(ex.id.equals(old.existingObjectId))
                        moved.tieChainage=new LengthIndexedLine(ex.geometry).project(position.getCoordinate());
                candidate.nodes.put(old.id,moved);
            }
            candidate.roots.clear();
            for(PlanNode root:plan.roots)candidate.roots.add(candidate.nodes.get(root.id));
            for(int k=0;k<plan.edges.size();k++) {
                PlanEdge original=plan.edges.get(k),edge=candidate.edges.get(k);
                edge.parent=candidate.nodes.get(original.parent.id);edge.child=candidate.nodes.get(original.child.id);
                edge.parent.children.add(edge);edge.child.parentEdge=edge;
                edge.geometry=routes.get(original)[assignment.selected().get(index.get(original.parent))][assignment.selected().get(index.get(original.child))];
            }
            evaluator.evaluate(s,candidate);
            if(candidate.score>=best.score-1e-8)continue;
            candidate.validation=validator.validate(s,candidate);
            if(candidate.validation.passed)best=candidate;
        }
        return best;
    }

    private List<Point> positions(InputSnapshot s, PlanNode node,double step) {
        List<Point> out=new ArrayList<>();out.add(node.point);
        if(node.kind==NodeKind.CHAMBER) {
            for(int angle=0;angle<8;angle++) out.add(gf.createPoint(new Coordinate(
                    node.point.getX()+step*Math.cos(angle*Math.PI/4),node.point.getY()+step*Math.sin(angle*Math.PI/4))));
        } else if(node.kind==NodeKind.TIE_IN&&"heat_network".equals(node.existingObjectType)) {
            for(InputSnapshot.ExistingEdge edge:s.network)if(edge.id.equals(node.existingObjectId)) {
                LengthIndexedLine line=new LengthIndexedLine(edge.geometry);
                for(double delta:new double[]{-step,step}) {
                    Point point=gf.createPoint(line.extractPoint(Math.max(0,Math.min(edge.geometry.getLength(),node.tieChainage+delta))));
                    // Preserve the mandatory existing-chamber snap rule.
                    if(s.chambers.stream().noneMatch(c->c.point.distance(point)<=10))out.add(point);
                }
            }
        }
        return out;
    }

    private double legalCost(InputSnapshot s,NetworkPlan plan,PlanEdge edge,LineString line,RouteConstraintEngine constraints) {
        if(line.getLength()<.05)return Double.POSITIVE_INFINITY;
        Coordinate[] c=line.getCoordinates();
        for(int i=0;i<c.length-1;i++) {
            Coordinate ownTerminal=edge.child.kind==NodeKind.TERMINAL&&i>=c.length-3?c[c.length-1]:null;
            Coordinate entryPort=ownTerminal!=null&&i==c.length-3?c[c.length-2]:null;
            if(!constraints.assess(gf.createLineString(new Coordinate[]{c[i],c[i+1]}),edge.dn,
                    Collections.emptyList(),c[0],plan.mode,ownTerminal,entryPort).feasible)return Double.POSITIVE_INFINITY;
            if(i>0&&(c[i].x-c[i-1].x)*(c[i+1].x-c[i].x)+(c[i].y-c[i-1].y)*(c[i+1].y-c[i].y)<-1e-6)
                return Double.POSITIVE_INFINITY;
        }
        PlanEdge trial=new PlanEdge(edge.id,edge.parent,edge.child,line);trial.dn=edge.dn;trial.flow=edge.flow;
        List<CostedRouteSegment> segments=costs.splitAndCost(s,trial,plan.mode);
        if(plan.mode==RunMode.DEPTH&&!costs.feasibleDepth(segments))return Double.POSITIVE_INFINITY;
        double cost=0;for(CostedRouteSegment segment:segments)cost+=segment.cost;
        return RuleBook.officialScore(cost,line.getLength());
    }
}

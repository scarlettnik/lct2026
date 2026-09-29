package ru.lct.teplokontur.optimization;

import java.util.*;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.teplokontur.config.CompetitionProperties;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.PlanEvaluator;
import ru.lct.teplokontur.routing.SparseVisibilityRouter;
import ru.lct.teplokontur.validation.EngineeringValidator;

/** Grows a routed forest, allowing new demands to share existing trunks. */
public final class SharedNetworkBuilder {
    private final GeometryFactory gf = new GeometryFactory();
    private final SparseVisibilityRouter router = new SparseVisibilityRouter();
    private final TieCandidateGenerator ties;
    private final PlanEvaluator evaluator = new PlanEvaluator();
    private final EngineeringValidator validator = new EngineeringValidator();
    private final int initialCandidates;
    private final int acceptedCandidates;

    public SharedNetworkBuilder(CompetitionProperties props) { ties = new TieCandidateGenerator(props);initialCandidates=Math.max(1,props.getOptimizer().getTieCandidates());acceptedCandidates=props.getOptimizer().isRefine()?3:1; }

    public NetworkPlan build(InputSnapshot snapshot, RunMode mode) { return build(snapshot,mode,0); }

    public NetworkPlan build(InputSnapshot snapshot, RunMode mode, int seed) {
        NetworkPlan plan = new NetworkPlan();
        plan.mode = mode;
        plan.policy = VariantPolicy.OFFICIAL_BEST;
        for (InputSnapshot.Terminal terminal : snapshot.terminals) plan.unconnected.add(terminal.oksId);
        evaluator.evaluate(snapshot, plan);
        Map<String, List<TieCandidate>> roots = new LinkedHashMap<>();
        for (InputSnapshot.Terminal terminal : snapshot.terminals)
            roots.put(terminal.oksId, ties.generate(snapshot, terminal, initialCandidates, seed));
        int candidateLimit=initialCandidates;
        while (!plan.unconnected.isEmpty()) {
            NetworkPlan best = null;
            double bestIncrement = Double.POSITIVE_INFINITY;
            List<InputSnapshot.Terminal> pending = new ArrayList<>(snapshot.terminals);
            final NetworkPlan current = plan;
            pending.removeIf(t -> !current.unconnected.contains(t.oksId));
            pending.sort(Comparator.comparingDouble(t -> proximity(current, roots.get(t.oksId), t) * (seed==0?1:.55+new SplittableRandom(Objects.hash(t.oksId,seed)).nextDouble())));
            for (InputSnapshot.Terminal terminal : pending) {
                List<Attachment> attachments = attachments(snapshot,plan, roots.get(terminal.oksId), terminal);
                List<LineString> occupied = plan.edges.stream().map(e -> e.geometry).collect(Collectors.toList());
                int accepted = 0;
                for (Attachment attachment : attachments) {
                    if (attachment.point.distance(terminal.point) * unitScore(terminal) >= bestIncrement) break;
                    int dn = RuleBook.minForFlow(terminal.flow).dn;
                    Optional<LineString> route = router.route(snapshot, attachment.point, terminal.point,
                            dn, mode, occupied, 0);
                    if (route.isEmpty()) continue;
                    NetworkPlan candidate = attach(plan, terminal, attachment, route.get());
                    evaluator.evaluate(snapshot, candidate);
                    candidate.validation = validator.validate(snapshot, candidate);
                    if (!candidate.validation.passed) continue;
                    double increment = candidate.score - current.score + .7 * RuleBook.unconnectedPenalty(terminal.flow) / 25_000_000.0;
                    if (increment < bestIncrement) { best = candidate; bestIncrement = increment; }
                    if (++accepted >= acceptedCandidates) break;
                }
                if (best != null) break;
            }
            if (best == null) {
                // A blocked nearest tie does not make the terminal unreachable.
                // Expand the search only after the inexpensive candidates fail.
                if(candidateLimit==Integer.MAX_VALUE)break;
                candidateLimit=candidateLimit<96?Math.min(96,candidateLimit*4):Integer.MAX_VALUE;
                for(InputSnapshot.Terminal terminal:pending)
                    roots.put(terminal.oksId,ties.generate(snapshot,terminal,candidateLimit,seed));
                continue;
            }
            plan = best;
            System.out.printf(Locale.ROOT, "SHARED connected=%d score=%.4f length=%.1f%n",
                    snapshot.terminals.size()-plan.unconnected.size(), plan.score, plan.length);
        }
        plan.validation = validator.validate(snapshot, plan);
        return plan;
    }

    private double unitScore(InputSnapshot.Terminal terminal) {
        return .003 + .7 * RuleBook.minForFlow(terminal.flow).newCost / 25_000_000.0;
    }

    /** Reconnect early leaves to trunks that became available later in construction. */
    public NetworkPlan reconnectLeaves(InputSnapshot snapshot, NetworkPlan plan) {
        List<InputSnapshot.Terminal> terminals=new ArrayList<>(snapshot.terminals);
        final NetworkPlan initial=plan;
        terminals.sort(Comparator.comparingDouble((InputSnapshot.Terminal t) -> {
            PlanNode n=initial.nodes.get(t.pointId);
            return n==null||n.parentEdge==null?0:-n.parentEdge.length;
        }));
        for(InputSnapshot.Terminal terminal:terminals) {
            PlanNode leaf=plan.nodes.get(terminal.pointId);
            if(leaf==null||leaf.parentEdge==null)continue;
            NetworkPlan detached=copy(plan);
            PlanNode removed=detached.nodes.remove(leaf.id);
            PlanEdge incoming=removed.parentEdge;
            detached.edges.remove(incoming); incoming.parent.children.remove(incoming);
            PlanNode ancestor=incoming.parent;
            while(ancestor.children.isEmpty()) {
                detached.nodes.remove(ancestor.id);detached.roots.remove(ancestor);
                if(ancestor.parentEdge==null)break;
                PlanEdge parent=ancestor.parentEdge;detached.edges.remove(parent);
                parent.parent.children.remove(parent);ancestor=parent.parent;
            }
            detached.unconnected.add(terminal.oksId);
            evaluator.evaluate(snapshot,detached);
            List<LineString> occupied=detached.edges.stream().map(e->e.geometry).collect(Collectors.toList());
            NetworkPlan best=plan;
            int accepted=0;
            double connectedBase=detached.score-.7*RuleBook.unconnectedPenalty(terminal.flow)/25_000_000.0;
            for(Attachment attachment:attachments(snapshot,detached,ties.generate(snapshot,terminal,16,0),terminal)) {
                // Compare complete plan cost: a longer leaf can remove a costly trunk or tie.
                if(connectedBase+attachment.point.distance(terminal.point)*unitScore(terminal)>=best.score)break;
                Optional<LineString> route=router.route(snapshot,attachment.point,terminal.point,
                        RuleBook.minForFlow(terminal.flow).dn,plan.mode,occupied,0);
                if(route.isEmpty())continue;
                NetworkPlan candidate=attach(detached,terminal,attachment,route.get());
                evaluator.evaluate(snapshot,candidate);
                if(candidate.score>=best.score-1e-8)continue;
                candidate.validation=validator.validate(snapshot,candidate);
                if(!candidate.validation.passed)continue;
                best=candidate;
                if(++accepted==3)break;
            }
            plan=best;
        }
        return plan;
    }

    private double proximity(NetworkPlan plan, List<TieCandidate> roots, InputSnapshot.Terminal terminal) {
        double distance = roots.stream().mapToDouble(r -> r.point.distance(terminal.point)).min().orElse(Double.POSITIVE_INFINITY);
        for (PlanEdge edge : plan.edges) distance = Math.min(distance, edge.geometry.distance(terminal.point));
        return distance;
    }

    private List<Attachment> attachments(InputSnapshot snapshot,NetworkPlan plan, List<TieCandidate> roots, InputSnapshot.Terminal terminal) {
        List<Attachment> result = new ArrayList<>();
        for (TieCandidate root : roots) if(tieAvailable(snapshot,plan,root)) result.add(new Attachment(root.point, root, null, null, 0));
        for (PlanNode node : plan.nodes.values()) {
            if (node.kind != NodeKind.TERMINAL && node.degree() < 4)
                result.add(new Attachment(node.point, null, node.id, null, 0));
        }
        for (PlanEdge edge : plan.edges) {
            double length = edge.geometry.getLength();
            if (length < 4) continue;
            LengthIndexedLine line = new LengthIndexedLine(edge.geometry);
            double projection = Math.max(2, Math.min(length-2, line.project(terminal.point.getCoordinate())));
            result.add(new Attachment(gf.createPoint(line.extractPoint(projection)), null, null, edge.id, projection));
            // A point outside an existing terminal's facade can form a valid branch.
            double setback = Math.max(2, length-12);
            if (Math.abs(projection-setback) > 4)
                result.add(new Attachment(gf.createPoint(line.extractPoint(setback)), null, null, edge.id, setback));
        }
        result.sort(Comparator.comparingDouble(a -> a.point.distance(terminal.point)));
        return result;
    }

    private boolean tieAvailable(InputSnapshot s,NetworkPlan p,TieCandidate tie) {
        if ("heat_chamber".equals(tie.type)) {
            InputSnapshot.ExistingChamber c=s.chambers.stream().filter(x->x.id.equals(tie.existingId)).findFirst().orElse(null);
            return c!=null && ExistingChamberRules.existingConnections(s,c)+ExistingChamberRules.plannedConnections(p,c.id)+1<=4;
        }
        for (PlanNode root:p.roots) if(tie.type.equals(root.existingObjectType)&&Objects.equals(tie.existingId,root.existingObjectId)
                &&root.point.distance(tie.point)<.05&&ExistingChamberRules.pipeTieConnections(s,root)+root.children.size()+1>4)return false;
        for(InputSnapshot.ExistingChamber c:s.chambers)if(c.point.distance(tie.point)<=10
                &&ExistingChamberRules.existingConnections(s,c)+ExistingChamberRules.plannedConnections(p,c.id)<4)return false;
        return true;
    }

    private NetworkPlan attach(NetworkPlan original, InputSnapshot.Terminal terminal, Attachment attachment, LineString route) {
        NetworkPlan plan = copy(original);
        int sequence = nextSequence(plan);
        PlanNode parent;
        if (attachment.nodeId != null) parent = plan.nodes.get(attachment.nodeId);
        else if (attachment.edgeId != null) {
            PlanEdge edge = plan.edges.stream().filter(e -> e.id.equals(attachment.edgeId)).findFirst().orElseThrow();
            parent = new PlanNode("branch_"+sequence++, NodeKind.CHAMBER, attachment.point);
            plan.nodes.put(parent.id, parent);
            LengthIndexedLine line = new LengthIndexedLine(edge.geometry);
            PlanNode child = edge.child;
            LineString downstream = (LineString) line.extractLine(attachment.chainage, edge.geometry.getLength());
            edge.geometry = (LineString) line.extractLine(0, attachment.chainage);
            edge.child = parent;
            parent.parentEdge = edge;
            addEdge(plan, "shared_"+sequence++, parent, child, downstream);
        } else {
            TieCandidate tie = attachment.tie;
            parent = plan.roots.stream().filter(n -> n.point.distance(tie.point)<.05).findFirst().orElse(null);
            if (parent == null) {
                parent = new PlanNode("root_"+sequence++, NodeKind.TIE_IN, tie.point);
                parent.existingObjectId=tie.existingId; parent.existingObjectType=tie.type;
                parent.existingDn=tie.existingDn; parent.tieChainage=tie.chainage;
                plan.roots.add(parent); plan.nodes.put(parent.id,parent);
            }
        }
        PlanNode leaf = new PlanNode(terminal.pointId, NodeKind.TERMINAL, terminal.point);
        leaf.oksId=terminal.oksId; plan.nodes.put(leaf.id,leaf);
        addEdge(plan, "shared_"+sequence, parent, leaf, route);
        plan.unconnected.remove(terminal.oksId);
        return plan;
    }

    private static void addEdge(NetworkPlan plan, String id, PlanNode parent, PlanNode child, LineString geometry) {
        PlanEdge edge=new PlanEdge(id,parent,child,geometry);
        parent.children.add(edge); child.parentEdge=edge; plan.edges.add(edge);
    }

    static NetworkPlan copy(NetworkPlan original) {
        NetworkPlan plan=original.copyShallow(); plan.unconnected.addAll(original.unconnected);
        for (PlanNode node : original.nodes.values()) {
            PlanNode copy=new PlanNode(node.id,node.kind,node.point);
            copy.oksId=node.oksId; copy.existingObjectId=node.existingObjectId;
            copy.existingObjectType=node.existingObjectType; copy.existingDn=node.existingDn; copy.tieChainage=node.tieChainage;
            plan.nodes.put(copy.id,copy);
        }
        for (PlanNode root : original.roots) plan.roots.add(plan.nodes.get(root.id));
        for (PlanEdge edge : original.edges)
            addEdge(plan,edge.id,plan.nodes.get(edge.parent.id),plan.nodes.get(edge.child.id),edge.geometry);
        return plan;
    }

    private int nextSequence(NetworkPlan plan) {
        int next=plan.nodes.size()+plan.edges.size()+1;
        Set<String> ids=new HashSet<>(plan.nodes.keySet());
        for(PlanEdge edge:plan.edges)ids.add(edge.id);
        for(String id:ids) {
            if(id.startsWith("root_")||id.startsWith("branch_")||id.startsWith("shared_")) {
                try { next=Math.max(next,Integer.parseInt(id.substring(id.lastIndexOf('_')+1))+1); }
                catch(NumberFormatException ignored) { /* External identifiers need not be numeric. */ }
            }
        }
        return next;
    }

    private static final class Attachment {
        final Point point; final TieCandidate tie; final String nodeId,edgeId; final double chainage;
        Attachment(Point point,TieCandidate tie,String nodeId,String edgeId,double chainage) {
            this.point=point;this.tie=tie;this.nodeId=nodeId;this.edgeId=edgeId;this.chainage=chainage;
        }
    }
}

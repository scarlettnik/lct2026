package ru.lct.teplokontur.engineering;

import ru.lct.teplokontur.domain.*;
import java.util.*;

/** Technical appendix sections 7–9: all construction and reconstruction work. */
public class PlanEvaluator {
    private final FlowDiameterCalculator fd = new FlowDiameterCalculator();
    private final SegmentCostModel costModel = new SegmentCostModel();
    private InputSnapshot cachedSnapshot;
    private final Map<LineCalculationKey,Double> constructionCosts = new LinkedHashMap<LineCalculationKey,Double>(256,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<LineCalculationKey,Double> entry) { return size()>8192; }
    };

    public ReconstructionEvaluator.Result evaluate(InputSnapshot s, NetworkPlan p) {
        if(cachedSnapshot!=s){cachedSnapshot=s;constructionCosts.clear();}
        Map<String,Double> flows = new HashMap<>();
        for (InputSnapshot.Terminal t:s.terminals) flows.put(t.oksId,t.flow);
        fd.apply(p,flows);
        p.constructionCost=0;p.newLength=0;
        for (PlanEdge e:p.edges) {
            e.cost=0;e.length=e.geometry.getLength();
            // Flow determines DN above; cost itself depends on geometry, DN and mode.
            LineCalculationKey key=new LineCalculationKey(e.geometry,e.dn,p.mode.ordinal());
            Double cached=constructionCosts.get(key);
            if(cached!=null)e.cost=cached;
            else {
                e.cost=costModel.constructionCost(s,e,p.mode);
                constructionCosts.put(key,e.cost);
            }
            p.constructionCost+=e.cost;p.newLength+=e.length;
        }
        p.tieInCost=p.roots.stream().filter(root->"heat_chamber".equals(root.existingObjectType))
                .mapToInt(root->root.children.size()).sum()*5_000_000.0;
        p.chamberCost=0;
        for (PlanNode n:p.nodes.values()) {
            boolean pipeTie=n.kind==NodeKind.TIE_IN&&"heat_network".equals(n.existingObjectType);
            if (pipeTie||n.kind==NodeKind.CHAMBER) {
                int max=n.parentEdge==null?0:n.parentEdge.dn;
                for (PlanEdge e:n.children) max=Math.max(max,e.dn);
                if(max>0)p.chamberCost+=RuleBook.chamberCost(max);
            }
        }
        ReconstructionEvaluator.Result rr=new ReconstructionEvaluator.Result();
        p.reconstructionCost=0;p.reconstructionLength=0;p.chamberReconstructionCost=0;
        p.unconnectedPenalty=0;
        for(String id:p.unconnected)p.unconnectedPenalty+=RuleBook.unconnectedPenalty(flows.getOrDefault(id,0.));
        p.calculatedCost=p.constructionCost+p.chamberCost+p.tieInCost+p.reconstructionCost+p.chamberReconstructionCost+p.unconnectedPenalty;
        p.length=p.newLength+p.reconstructionLength;
        p.score=RuleBook.officialScore(p.calculatedCost,p.length);
        return rr;
    }

    public List<CostedRouteSegment> segments(InputSnapshot s,PlanEdge e,RunMode mode) {
        return costModel.splitAndCost(s,e,mode);
    }
}

package ru.lct.teplokontur.engineering;

import java.util.*;
import ru.lct.teplokontur.domain.*;

/** Minimum DN that satisfies flow and per-path same-DN length limits. */
public final class PipeSizing {
    private PipeSizing() { }

    public static Map<PlanEdge,Integer> requiredDiameters(NetworkPlan plan,Map<String,Double> terminalFlows) {
        Map<PlanEdge,Integer> diameters=new IdentityHashMap<>();
        for(PlanNode root:plan.roots)flow(root,terminalFlows,diameters);
        List<List<PlanEdge>> paths=new ArrayList<>();
        for(PlanNode root:plan.roots)collectPaths(root,new ArrayList<>(),paths);
        int limit=Math.max(1,plan.edges.size()*RuleBook.DN.size()+1);
        for(int iteration=0;iteration<limit;iteration++) {
            boolean changed=false;
            for(List<PlanEdge> path:paths) {
                // Path order is from the OKS toward the existing network.
                int requiredTowardRoot=0;
                for(PlanEdge edge:path) {
                    int dn=diameters.get(edge);
                    if(dn<requiredTowardRoot){diameters.put(edge,requiredTowardRoot);dn=requiredTowardRoot;changed=true;}
                    requiredTowardRoot=dn;
                }
                for(int start=0;start<path.size();) {
                    int end=start+1,dn=diameters.get(path.get(start));
                    while(end<path.size()&&diameters.get(path.get(end))==dn)end++;
                    double length=0,flow=0;
                    for(int i=start;i<end;i++){PlanEdge edge=path.get(i);length+=edge.geometry.getLength();flow=Math.max(flow,edge.flow);}
                    final int current=dn;
                    final double runLength=length,requiredFlow=flow;
                    DnSpec selected=RuleBook.DN.stream().filter(spec->spec.dn>=current
                            &&spec.capacity+1e-9>=requiredFlow&&spec.maxLength+1e-9>=runLength).findFirst()
                            .orElseThrow(()->new IllegalArgumentException("No DN satisfies flow and path length: "+requiredFlow+" tph / "+runLength+" m"));
                    if(selected.dn>dn){for(int i=start;i<end;i++)diameters.put(path.get(i),selected.dn);changed=true;}
                    start=end;
                }
            }
            if(!changed)return diameters;
        }
        throw new IllegalStateException("Pipe sizing did not converge");
    }

    private static double flow(PlanNode node,Map<String,Double> terminalFlows,Map<PlanEdge,Integer> diameters) {
        double total=node.kind==NodeKind.TERMINAL?terminalFlows.getOrDefault(node.oksId,0.0):0;
        for(PlanEdge edge:node.children) {
            edge.flow=flow(edge.child,terminalFlows,diameters);
            int min=RuleBook.minForFlow(edge.flow).dn;edge.minDn=min;diameters.put(edge,min);total+=edge.flow;
        }
        return total;
    }

    private static void collectPaths(PlanNode node,List<PlanEdge> downstream,List<List<PlanEdge>> paths) {
        if(node.kind==NodeKind.TERMINAL){List<PlanEdge> path=new ArrayList<>(downstream);Collections.reverse(path);if(!path.isEmpty())paths.add(path);return;}
        for(PlanEdge edge:node.children){downstream.add(edge);collectPaths(edge.child,downstream,paths);downstream.remove(downstream.size()-1);}
    }
}

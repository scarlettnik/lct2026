package ru.lct.teplokontur.optimization;
import org.locationtech.jts.geom.*;import ru.lct.teplokontur.domain.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
public class PlanFactory {
 private final AtomicInteger seq=new AtomicInteger();
 public PlanNode root(NetworkPlan p,TieCandidate tc){for(PlanNode existing:p.roots)if(Objects.equals(existing.existingObjectId,tc.existingId)&&Objects.equals(existing.existingObjectType,tc.type)&&existing.point.distance(tc.point)<.05)return existing;PlanNode n=new PlanNode("r_"+seq.incrementAndGet(),NodeKind.TIE_IN,tc.point);n.existingObjectId=tc.existingId;n.existingObjectType=tc.type;n.existingDn=tc.existingDn;n.tieChainage=tc.chainage;p.nodes.put(n.id,n);p.roots.add(n);return n;}
 public PlanNode chamber(NetworkPlan p,Point pt){PlanNode n=new PlanNode("c_"+seq.incrementAndGet(),NodeKind.CHAMBER,pt);p.nodes.put(n.id,n);return n;}
 public PlanNode terminal(NetworkPlan p,InputSnapshot.Terminal t){PlanNode n=new PlanNode(t.pointId,NodeKind.TERMINAL,t.point);n.oksId=t.oksId;p.nodes.put(n.id,n);return n;}
 public PlanEdge edge(NetworkPlan p,PlanNode parent,PlanNode child,LineString ls){PlanEdge e=new PlanEdge("e_"+seq.incrementAndGet(),parent,child,ls);parent.children.add(e);child.parentEdge=e;p.edges.add(e);return e;}
}

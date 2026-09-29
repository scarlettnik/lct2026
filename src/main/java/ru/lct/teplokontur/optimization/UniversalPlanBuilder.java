package ru.lct.teplokontur.optimization;

import org.locationtech.jts.geom.*;
import ru.lct.teplokontur.config.CompetitionProperties;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.routing.SparseVisibilityRouter;
import java.util.*;

public class UniversalPlanBuilder {
 private final CompetitionProperties props;private final TieCandidateGenerator ties;private final GeometryFactory gf=new GeometryFactory();private final SparseVisibilityRouter router=new SparseVisibilityRouter();
 public UniversalPlanBuilder(CompetitionProperties props){this.props=props;this.ties=new TieCandidateGenerator(props);}
 public NetworkPlan build(InputSnapshot s,RunMode mode,VariantPolicy policy,int salt){NetworkPlan p=new NetworkPlan();p.mode=mode;p.policy=policy;p.variantId=policy.name().toLowerCase()+"_"+mode.name().toLowerCase();PlanFactory f=new PlanFactory();List<LineString> occupied=new ArrayList<>();
   if(policy==VariantPolicy.OFFICIAL_BEST)buildClustered(s,p,f,router,occupied,salt);else buildIndependent(s,p,f,router,occupied,salt,policy==VariantPolicy.FULL_COVERAGE);return p;}
 private void buildIndependent(InputSnapshot s,NetworkPlan p,PlanFactory f,SparseVisibilityRouter router,List<LineString> occupied,int salt,boolean aggressive){List<InputSnapshot.Terminal> ts=new ArrayList<>(s.terminals);if(aggressive)ts.sort(Comparator.comparingDouble((InputSnapshot.Terminal t)->nearestDistance(s,t)).reversed().thenComparing(t->t.oksId));else ts.sort(Comparator.comparingDouble((InputSnapshot.Terminal t)->-t.flow).thenComparing(t->t.oksId));
   // Retain nearby roots independently of kNN density. Eight
   // Java candidates silently discarded viable components behind a restriction;
   // keep a real root frontier and let exact routing/evaluation choose it.
   int base=Math.max(32,props.getOptimizer().getTieCandidates());int limit=aggressive?Math.max(64,base*2):base;for(InputSnapshot.Terminal t:ts){boolean ok=connectOne(s,p,f,router,occupied,t,limit,salt+(int)(31*t.flow));if(!ok)p.unconnected.add(t.oksId);}}
 private void buildClustered(InputSnapshot s,NetworkPlan p,PlanFactory f,SparseVisibilityRouter router,List<LineString> occupied,int salt){List<InputSnapshot.Terminal> rest=new ArrayList<>(s.terminals);rest.sort(Comparator.comparingDouble((InputSnapshot.Terminal t)->-t.flow).thenComparing(t->t.oksId));Set<String> used=new HashSet<>();for(InputSnapshot.Terminal t:rest){if(used.contains(t.oksId))continue;List<InputSnapshot.Terminal> group=new ArrayList<>();group.add(t);List<InputSnapshot.Terminal> near=new ArrayList<>(rest);near.removeIf(x->used.contains(x.oksId)||x==t);near.sort(Comparator.comparingDouble(x->x.point.distance(t.point)));for(InputSnapshot.Terminal x:near){if(group.size()>=3)break;double spread=x.point.distance(t.point);double scale=Math.min(nearestDistance(s,t),nearestDistance(s,x));if(spread<Math.max(props.getRouting().getGridStepM()*4.0,scale*.9))group.add(x);}
     boolean ok=group.size()>1&&connectCluster(s,p,f,router,occupied,group,salt+group.size()*97);if(ok){for(var x:group)used.add(x.oksId);}else{ok=connectOne(s,p,f,router,occupied,t,Math.max(32,props.getOptimizer().getTieCandidates()),salt+13);used.add(t.oksId);if(!ok)p.unconnected.add(t.oksId);}}
 }
 private boolean connectCluster(InputSnapshot s,NetworkPlan p,PlanFactory f,SparseVisibilityRouter router,List<LineString> occupied,List<InputSnapshot.Terminal> group,int salt){double flow=group.stream().mapToDouble(t->t.flow).sum();int trunkDn=RuleBook.minForFlow(flow).dn;List<Point> junctions=junctionCandidates(group);for(Point j:junctions){InputSnapshot.Terminal pseudo=new InputSnapshot.Terminal();pseudo.point=j;pseudo.flow=flow;pseudo.oksId="cluster";List<TieCandidate> tc=ties.generate(s,pseudo,props.getOptimizer().getTieCandidates(),salt);for(TieCandidate tie:tc){if(!tieAvailable(s,p,tie))continue;Optional<LineString> trunk=router.route(s,tie.point,j,trunkDn,p.mode,occupied,salt);if(trunk.isEmpty())continue;List<LineString> localOcc=new ArrayList<>(occupied);localOcc.add(trunk.get());List<LineString> branches=new ArrayList<>();boolean all=true;for(InputSnapshot.Terminal t:group){int dn=RuleBook.minForFlow(t.flow).dn;Optional<LineString> br=router.route(s,j,t.point,dn,p.mode,localOcc,salt+Objects.hash(t.oksId));if(br.isEmpty()){all=false;break;}branches.add(br.get());localOcc.add(br.get());}if(!all)continue;
       PlanNode root=f.root(p,tie);PlanNode ch=f.chamber(p,j);f.edge(p,root,ch,trunk.get());for(int i=0;i<group.size();i++){PlanNode tn=f.terminal(p,group.get(i));f.edge(p,ch,tn,branches.get(i));}occupied.addAll(localOcc.subList(occupied.size(),localOcc.size()));return true;}}
   return false;}
 private boolean connectOne(InputSnapshot s,NetworkPlan p,PlanFactory f,SparseVisibilityRouter router,List<LineString> occupied,InputSnapshot.Terminal t,int limit,int salt){int dn=RuleBook.minForFlow(t.flow).dn;for(TieCandidate tie:ties.generate(s,t,limit,salt)){if(!tieAvailable(s,p,tie))continue;Optional<LineString> r=router.route(s,tie.point,t.point,dn,p.mode,occupied,salt+Objects.hash(t.oksId,tie.existingId));if(r.isEmpty())continue;PlanNode root=f.root(p,tie),tn=f.terminal(p,t);f.edge(p,root,tn,r.get());occupied.add(r.get());return true;}return false;}
 private List<Point> junctionCandidates(List<InputSnapshot.Terminal> g){double sx=0,sy=0,sw=0;for(var t:g){double w=Math.max(1,t.flow);sx+=t.point.getX()*w;sy+=t.point.getY()*w;sw+=w;}List<Point> out=new ArrayList<>();out.add(gf.createPoint(new Coordinate(sx/sw,sy/sw)));double ax=g.stream().mapToDouble(t->t.point.getX()).average().orElse(sx/sw),ay=g.stream().mapToDouble(t->t.point.getY()).average().orElse(sy/sw);out.add(gf.createPoint(new Coordinate(ax,ay)));for(var t:g)out.add(t.point);return out;}
 private boolean tieAvailable(InputSnapshot s,NetworkPlan p,TieCandidate tie){
   if(!"heat_chamber".equals(tie.type)){
     for(PlanNode r:p.roots)if(tie.type.equals(r.existingObjectType)&&tie.existingId.equals(r.existingObjectId)
             &&r.point.distance(tie.point)<.05&&ExistingChamberRules.pipeTieConnections(s,r)+r.children.size()+1>4)return false;
     for(InputSnapshot.ExistingChamber c:s.chambers)if(c.point.distance(tie.point)<=10
             &&ExistingChamberRules.existingConnections(s,c)+ExistingChamberRules.plannedConnections(p,c.id)<4)return false;
     return true;
   }
   InputSnapshot.ExistingChamber chamber=null;for(InputSnapshot.ExistingChamber c:s.chambers)if(c.id.equals(tie.existingId)){chamber=c;break;}
   return chamber!=null&&ExistingChamberRules.existingConnections(s,chamber)+ExistingChamberRules.plannedConnections(p,chamber.id)+1<=4;
 }

 private double nearestDistance(InputSnapshot s,InputSnapshot.Terminal t){double d=Double.POSITIVE_INFINITY;for(var e:s.network)d=Math.min(d,e.geometry.distance(t.point));for(var c:s.chambers)d=Math.min(d,c.point.distance(t.point));return d;}
}

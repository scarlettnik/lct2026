package ru.lct.teplokontur.validation;

import org.locationtech.jts.geom.*;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.PipeSizing;
import ru.lct.teplokontur.routing.RouteAssessment;
import ru.lct.teplokontur.routing.RouteConstraintEngine;
import ru.lct.teplokontur.domain.RestrictionRule;
import ru.lct.teplokontur.domain.RestrictionRules;
import java.util.*;

/** Independent engineering checks for every generated plan. */
public class EngineeringValidator {
    private InputSnapshot cachedSnapshot;
    private RouteConstraintEngine cachedConstraints;

    public ValidationReport validate(InputSnapshot s,NetworkPlan p) {
        ValidationReport r=new ValidationReport();checkTopology(s,p,r);checkFlowsAndDn(s,p,r);checkGeometry(s,p,r);
        new DepthProfileValidator().validate(s,p,r);checkScore(p,r);r.finish();return r;
    }

    private void checkTopology(InputSnapshot s,NetworkPlan p,ValidationReport r) {
        Set<String> terms=new HashSet<>();
        for(PlanNode n:p.nodes.values()) {
            if(n.degree()>4)r.error("Node degree >4: "+n.id);
            if(n.kind==NodeKind.TERMINAL){if(!n.children.isEmpty())r.error("Terminal used as transit: "+n.id);if(n.parentEdge==null)r.error("Connected terminal has no parent: "+n.id);if(!terms.add(n.oksId))r.error("Duplicate terminal: "+n.oksId);}
            if(n.kind==NodeKind.TIE_IN&&n.parentEdge!=null)r.error("Tie root has parent: "+n.id);
            if(!p.roots.contains(n)&&n.kind!=NodeKind.TERMINAL&&n.parentEdge==null)r.error("Orphan node: "+n.id);
        }
        Map<String,Integer> newAtChamber=new HashMap<>();
        for(PlanNode root:p.roots)if("heat_chamber".equals(root.existingObjectType))newAtChamber.merge(root.existingObjectId,root.children.size(),Integer::sum);
        for(InputSnapshot.ExistingChamber c:s.chambers)if(ExistingChamberRules.existingConnections(s,c)+newAtChamber.getOrDefault(c.id,0)>4)r.error("Existing chamber degree >4: "+c.id);
        for(PlanNode root:p.roots)if(!"heat_chamber".equals(root.existingObjectType)) {
            if(ExistingChamberRules.pipeTieConnections(s,root)+root.children.size()>4)r.error("Pipe tie chamber degree >4: "+root.id);
            for(InputSnapshot.ExistingChamber c:s.chambers)if(c.point.distance(root.point)<=10
                    &&ExistingChamberRules.existingConnections(s,c)+newAtChamber.getOrDefault(c.id,0)<4)
                r.error("Must attach to eligible existing chamber within 10 m: "+root.id);
        }
        Set<String> expected=new HashSet<>();for(var t:s.terminals)expected.add(t.oksId);
        Set<String> union=new HashSet<>(terms);union.addAll(p.unconnected);if(!union.equals(expected))r.error("Connected + unconnected OKS set does not match input");
        for(PlanNode root:p.roots){if(root.parentEdge!=null)r.error("Root has parent: "+root.id);if(!acyclic(root,Collections.newSetFromMap(new IdentityHashMap<>())))r.error("Cycle in component "+root.id);}
    }

    private boolean acyclic(PlanNode n,Set<PlanNode> seen){if(!seen.add(n))return false;for(PlanEdge e:n.children)if(!acyclic(e.child,seen))return false;return true;}

    private void checkFlowsAndDn(InputSnapshot s,NetworkPlan p,ValidationReport r) {
        Map<String,Double> flows=new HashMap<>();for(var t:s.terminals)flows.put(t.oksId,t.flow);
        try {
            Map<PlanEdge,Integer> required=PipeSizing.requiredDiameters(p,flows);
            for(PlanEdge e:p.edges){Integer expected=required.get(e);if(expected==null||e.dn!=expected)r.error("Non-minimal or inconsistent DN on "+e.id+": got "+e.dn+", required "+expected);}
        } catch(RuntimeException ex){r.error("Pipe sizing constraints cannot be satisfied: "+ex.getMessage());}
        for(String oks:p.unconnected)if(flows.getOrDefault(oks,0.)<0)r.error("Invalid unconnected terminal flow: "+oks);
    }

    private void checkGeometry(InputSnapshot s,NetworkPlan p,ValidationReport r) {
        if(cachedSnapshot!=s){cachedSnapshot=s;cachedConstraints=new RouteConstraintEngine(s);}RouteConstraintEngine ce=cachedConstraints;
        for(PlanEdge e:p.edges){Coordinate[] c=e.geometry.getCoordinates();if(c[0].distance(e.parent.point.getCoordinate())>.001||c[c.length-1].distance(e.child.point.getCoordinate())>.001)r.error("Edge endpoint mismatch: "+e.id);
            for(InputSnapshot.Restriction restriction:s.restrictions){RestrictionRule rule=RestrictionRules.get(restriction.type);if(rule==null||!rule.crossingAllowed||!e.geometry.intersects(restriction.geometry))continue;for(int k=1;k<c.length-1;k++)if(restriction.geometry.covers(e.geometry.getFactory().createPoint(c[k]))&&turn(c[k-1],c[k],c[k+1])>.25)r.error("Special crossing must be one straight section: "+e.id+" / "+restriction.type);}
            for(int i=0;i<c.length-1;i++){LineString seg=e.geometry.getFactory().createLineString(new Coordinate[]{c[i],c[i+1]});Coordinate own=e.child.kind==NodeKind.TERMINAL&&i>=c.length-3?e.child.point.getCoordinate():null;Coordinate port=own!=null&&i==c.length-3?c[c.length-2]:null;RouteAssessment a=ce.assess(seg,e.dn,Collections.emptyList(),e.parent.point.getCoordinate(),p.mode,own,port);if(!a.feasible)r.error("Restriction violation "+e.id+": "+a.reason);}
            for(int i=1;i<c.length-1;i++){double angle=turn(c[i-1],c[i],c[i+1]);if(angle>90.0001)r.error("Turn >90 at "+e.id+": "+angle);}
        }
        for(int i=0;i<p.edges.size();i++)for(int j=i+1;j<p.edges.size();j++){PlanEdge a=p.edges.get(i),b=p.edges.get(j);Geometry inter=a.geometry.intersection(b.geometry);if(inter.isEmpty())continue;boolean share=a.parent==b.parent||a.parent==b.child||a.child==b.parent||a.child==b.child;if(!share||inter.getDimension()>=1&&inter.getLength()>.05)r.error("Illegal new-network intersection: "+a.id+" / "+b.id);}
    }

    private void checkScore(NetworkPlan p,ValidationReport r){double cost=p.constructionCost+p.chamberCost+p.tieInCost+p.reconstructionCost+p.chamberReconstructionCost+p.unconnectedPenalty;double length=p.newLength+p.reconstructionLength;double score=RuleBook.officialScore(cost,length);if(Math.abs(cost-p.calculatedCost)>.1)r.error("Calculated cost mismatch");if(Math.abs(length-p.length)>.001)r.error("Length mismatch");if(Math.abs(score-p.score)>1e-8)r.error("Official score mismatch");}
    private static double turn(Coordinate a,Coordinate b,Coordinate c){double ux=b.x-a.x,uy=b.y-a.y,vx=c.x-b.x,vy=c.y-b.y,d=Math.hypot(ux,uy)*Math.hypot(vx,vy);if(d<1e-9)return 0;return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(ux*vx+uy*vy)/d))));}
}

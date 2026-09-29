package ru.lct.teplokontur.optimization;
import org.locationtech.jts.geom.*;import org.locationtech.jts.linearref.LengthIndexedLine;import ru.lct.teplokontur.config.CompetitionProperties;import ru.lct.teplokontur.domain.*;import java.util.*;
public class TieCandidateGenerator {
  private final GeometryFactory gf=new GeometryFactory();private final CompetitionProperties props;
  private InputSnapshot cachedSnapshot;
  private final Map<InputSnapshot.ExistingChamber,Integer> degrees=new IdentityHashMap<>();
  private final Map<InputSnapshot.ExistingEdge,List<TieCandidate>> samples=new IdentityHashMap<>();
  public TieCandidateGenerator(CompetitionProperties props){this.props=props;}
  public List<TieCandidate> generate(InputSnapshot s,InputSnapshot.Terminal t,int limit,int salt){prepare(s);List<TieCandidate> all=new ArrayList<>();
    for(InputSnapshot.ExistingChamber c:s.chambers)all.add(new TieCandidate(c.id,"heat_chamber",c.point,c.dn,-1));
    double spacing=Math.max(10.0,props.getRouting().getGridStepM()*8.0);for(InputSnapshot.ExistingEdge e:s.network){LengthIndexedLine li=new LengthIndexedLine(e.geometry);double len=e.geometry.getLength();double projected=li.project(t.point.getCoordinate());TieCandidate projectedTie=new TieCandidate(e.id,"heat_network",gf.createPoint(li.extractPoint(projected)),e.dn,projected);all.add(canonicalize(s,projectedTie));all.add(projectedTie);all.addAll(samples.get(e));}
    Map<TieCandidate,Double> ranks=new IdentityHashMap<>();for(TieCandidate c:all)ranks.put(c,score(t,c,s,salt));all.sort(Comparator.comparingDouble(ranks::get));List<TieCandidate> out=new ArrayList<>();Set<String> seen=new HashSet<>();for(TieCandidate c:all){String k=c.type+":"+c.existingId+":"+Math.round(c.point.getX()*10)+":"+Math.round(c.point.getY()*10);if(seen.add(k))out.add(c);if(out.size()>=limit)break;}return out;}
  private void prepare(InputSnapshot s) {
    if(cachedSnapshot==s)return;
    cachedSnapshot=s;degrees.clear();samples.clear();
    double spacing=Math.max(10.0,props.getRouting().getGridStepM()*8.0);
    for(InputSnapshot.ExistingEdge e:s.network) {
      List<TieCandidate> candidates=new ArrayList<>();
      LengthIndexedLine line=new LengthIndexedLine(e.geometry);double length=e.geometry.getLength();
      TieCandidate end=new TieCandidate(e.id,"heat_network",gf.createPoint(line.extractPoint(length)),e.dn,length);
      candidates.add(canonicalize(s,end));candidates.add(end);
      for(double d=0;d<=length+1e-6;d+=spacing) {
        double at=Math.min(d,length);
        candidates.add(canonicalize(s,new TieCandidate(e.id,"heat_network",gf.createPoint(line.extractPoint(at)),e.dn,at)));
      }
      samples.put(e,candidates);
    }
  }
  private TieCandidate canonicalize(InputSnapshot s,TieCandidate x){InputSnapshot.ExistingChamber best=null;double bd=10.000001;for(InputSnapshot.ExistingChamber c:s.chambers){double d=c.point.distance(x.point);if(d<bd&&existingDegree(s,c)<=3){best=c;bd=d;}}return best==null?x:new TieCandidate(best.id,"heat_chamber",best.point,best.dn,-1);}
  private int existingDegree(InputSnapshot s,InputSnapshot.ExistingChamber c){return degrees.computeIfAbsent(c,key->ExistingChamberRules.existingConnections(s,key));}
  private double score(InputSnapshot.Terminal t,TieCandidate c,InputSnapshot s,int salt){double d=t.point.distance(c.point);double capacitySlack=Math.max(0,t.flow-RuleBook.byDn(c.existingDn).capacity);double reconRisk=capacitySlack*25_000;double chamberBonus="heat_chamber".equals(c.type)?-5:0;double diversity=((Objects.hash(c.existingId,salt)&1023)/1023.0)*2.0;return d+reconRisk/107142.86+chamberBonus+diversity;}
}

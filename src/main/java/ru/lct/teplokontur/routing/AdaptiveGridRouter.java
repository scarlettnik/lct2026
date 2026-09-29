package ru.lct.teplokontur.routing;
import org.locationtech.jts.geom.*;import ru.lct.teplokontur.config.CompetitionProperties;import ru.lct.teplokontur.domain.*;import java.util.*;
public class AdaptiveGridRouter {
 private final CompetitionProperties props;private final GeometryFactory gf=new GeometryFactory();
 public AdaptiveGridRouter(CompetitionProperties props){this.props=props;}
 public Optional<LineString> route(InputSnapshot s,Point start,Point goal,int dn,RunMode mode,List<LineString> occupied,long salt){
   double base=props.getRouting().getGridStepM();double[] steps={Math.max(base*2,5),base,Math.max(1.5,base/2)};double[] pads={props.getRouting().getCorridorPaddingM(),props.getRouting().getCorridorPaddingM()*2,props.getRouting().getCorridorPaddingM()*4};
   for(double step:steps)for(double pad:pads){Optional<LineString> r=routeOnce(s,start,goal,dn,mode,occupied,step,pad,salt);if(r.isPresent())return r;}return Optional.empty();
 }
 private Optional<LineString> routeOnce(InputSnapshot s,Point start,Point goal,int dn,RunMode mode,List<LineString> occupied,double step,double pad,long salt){
   Envelope env=new Envelope(start.getCoordinate(),goal.getCoordinate());env.expandBy(pad);int nx=(int)Math.ceil(env.getWidth()/step)+1,ny=(int)Math.ceil(env.getHeight()/step)+1;long cells=(long)nx*ny;if(cells>props.getRouting().getMaxExpandedCells())return Optional.empty();
   RouteConstraintEngine ce=new RouteConstraintEngine(s);Coordinate touch=start.getCoordinate();
   class N {int x,y;double g,f;N prev;N(int x,int y,double g,double f,N p){this.x=x;this.y=y;this.g=g;this.f=f;this.prev=p;}}
   Comparator<N> cmp=Comparator.comparingDouble((N n)->n.f).thenComparingInt(n->n.x).thenComparingInt(n->n.y);PriorityQueue<N> q=new PriorityQueue<>(cmp);double[][] best=new double[nx][ny];for(double[] a:best)Arrays.fill(a,Double.POSITIVE_INFINITY);
   int sx=clamp((int)Math.round((start.getX()-env.getMinX())/step),0,nx-1),sy=clamp((int)Math.round((start.getY()-env.getMinY())/step),0,ny-1),gx=clamp((int)Math.round((goal.getX()-env.getMinX())/step),0,nx-1),gy=clamp((int)Math.round((goal.getY()-env.getMinY())/step),0,ny-1);
   N sn=new N(sx,sy,0,heur(sx,sy,gx,gy,step),null);q.add(sn);best[sx][sy]=0;int expanded=0;int[][] dirs={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};N end=null;
   while(!q.isEmpty()&&expanded++<props.getRouting().getMaxExpandedCells()){N n=q.poll();if(n.g>best[n.x][n.y]+1e-9)continue;if(Math.abs(n.x-gx)<=1&&Math.abs(n.y-gy)<=1){end=n;break;}Coordinate a=coord(env,n.x,n.y,step);
    for(int k=0;k<dirs.length;k++){int xx=n.x+dirs[k][0],yy=n.y+dirs[k][1];if(xx<0||yy<0||xx>=nx||yy>=ny)continue;Coordinate b=coord(env,xx,yy,step);LineString ls=gf.createLineString(new Coordinate[]{a,b});RouteAssessment ra=ce.assess(ls,dn,occupied,touch,mode);if(!ra.feasible)continue;double bend=0;if(n.prev!=null){Coordinate p=coord(env,n.prev.x,n.prev.y,step);double ang=turnAngle(p,a,b);if(ang>90.0001)continue;bend=ang<1?0:(near(ang,45)||near(ang,90)?0.1:0.35)*step;}
      double ng=n.g+ls.getLength()*ra.multiplier+bend+1e-8*((xx*73856093L^yy*19349663L^salt)&1023);if(ng+1e-9<best[xx][yy]){best[xx][yy]=ng;q.add(new N(xx,yy,ng,ng+heur(xx,yy,gx,gy,step),n));}
    }}
   if(end==null)return Optional.empty();List<Coordinate> cs=new ArrayList<>();cs.add(goal.getCoordinate());for(N n=end;n!=null;n=n.prev)cs.add(coord(env,n.x,n.y,step));cs.add(start.getCoordinate());Collections.reverse(cs);cs=simplify(cs,ce,dn,occupied,touch,mode);LineString out=gf.createLineString(cs.toArray(new Coordinate[0]));Coordinate[] oc=out.getCoordinates();for(int i=0;i<oc.length-1;i++){LineString chk=gf.createLineString(new Coordinate[]{oc[i],oc[i+1]});if(!ce.assess(chk,dn,occupied,touch,mode).feasible)return Optional.empty();}for(int i=1;i<oc.length-1;i++)if(turnAngle(oc[i-1],oc[i],oc[i+1])>90.0001)return Optional.empty();return Optional.of(out);
 }
 private List<Coordinate> simplify(List<Coordinate> in,RouteConstraintEngine ce,int dn,List<LineString> occ,Coordinate touch,RunMode mode){List<Coordinate> a=new ArrayList<>();for(Coordinate c:in)if(a.isEmpty()||c.distance(a.get(a.size()-1))>.05)a.add(c);boolean changed=true;while(changed){changed=false;for(int i=0;i+2<a.size();i++){LineString s=gf.createLineString(new Coordinate[]{a.get(i),a.get(i+2)});if(ce.assess(s,dn,occ,touch,mode).feasible&&turnContextOk(a,i)){a.remove(i+1);changed=true;break;}}}return a;}
 private boolean turnContextOk(List<Coordinate>a,int i){if(i>0&&turnAngle(a.get(i-1),a.get(i),a.get(i+2))>90.0001)return false;if(i+3<a.size()&&turnAngle(a.get(i),a.get(i+2),a.get(i+3))>90.0001)return false;return true;}
 private static boolean near(double a,double b){return Math.abs(a-b)<3;}private static double turnAngle(Coordinate a,Coordinate b,Coordinate c){double ux=b.x-a.x,uy=b.y-a.y,vx=c.x-b.x,vy=c.y-b.y;double d=Math.hypot(ux,uy)*Math.hypot(vx,vy);if(d<1e-9)return 0;return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(ux*vx+uy*vy)/d))));}
 private static double heur(int x,int y,int gx,int gy,double s){return Math.hypot(x-gx,y-gy)*s;}private static Coordinate coord(Envelope e,int x,int y,double s){return new Coordinate(e.getMinX()+x*s,e.getMinY()+y*s);}private static int clamp(int v,int a,int b){return Math.max(a,Math.min(b,v));}
}

package ru.lct.teplokontur.domain;
import java.util.*;
public final class RuleBook {
  private RuleBook() {}
  public static final List<DnSpec> DN = Collections.unmodifiableList(Arrays.asList(
    d(50,3.5,181,74023,96180,.125,.400,.125), d(65,8.3,245,78631,109989,.140,.430,.140),
    d(80,13.2,327,83530,117582,.160,.470,.160), d(100,22.3,419,89748,133694,.180,.510,.180),
    d(125,40.2,554,97275,148030,.225,.600,.225), d(150,65.1,696,105507,152295,.250,.650,.250),
    d(200,152.3,1042,120275,181766,.315,.880,.315), d(250,274.9,1379,135323,202030,.400,1.050,.400),
    d(300,437.4,1718,150022,228707,.450,1.150,.450), d(400,943.1,2477,190299,271317,.560,1.370,.560),
    d(500,1663.4,3245,224137,333884,.710,1.670,.710), d(600,2627.7,4037,264790,372703,.800,1.850,.800),
    d(700,3735.1,4775,324298,439571,.900,2.050,.900), d(800,5296.8,5644,325996,489918,1.000,2.250,1.000),
    d(900,7165.0,6518,327693,553607,1.100,2.450,1.100), d(1000,9391.8,7419,418777,606679,1.200,2.650,1.200),
    d(1200,15012.8,9288,428074,825692,1.425,3.100,1.425), d(1400,22501.9,11276,683417,978584,1.600,3.450,1.600)
  ));
  private static DnSpec d(int dn,double c,double l,double n,double r,double od,double w,double h){return new DnSpec(dn,c,l,n,r,od,w,h);}  
  public static DnSpec byDn(int dn){return DN.stream().filter(x->x.dn==dn).findFirst().orElseThrow(()->new IllegalArgumentException("Unsupported DN "+dn));}
  public static DnSpec minForFlow(double flow){return DN.stream().filter(x->x.capacity+1e-9>=flow).findFirst().orElseThrow(()->new IllegalArgumentException("Flow exceeds DN1400 capacity: "+flow));}
  public static DnSpec promoteOne(DnSpec x){int i=DN.indexOf(x);return i<DN.size()-1?DN.get(i+1):x;}
  public static double chamberCost(int maxDn){return maxDn<=200?3_000_000:maxDn<=500?5_000_000:maxDn<=1000?8_000_000:12_000_000;}
  public static double unconnectedPenalty(double flow){return 100_000_000+500_000*flow;}
  public static double officialScore(double cost,double length){return .7*(cost/25_000_000.0)+.3*(length/100.0);}  
  public static double depthFactor(double depth){return depth<=3?1.0:1.0+.10*(depth-3.0);}  
}

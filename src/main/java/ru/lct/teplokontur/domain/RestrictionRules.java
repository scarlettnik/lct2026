package ru.lct.teplokontur.domain;
import java.util.*;
public final class RestrictionRules {
  private static final Map<String,RestrictionRule> M=new HashMap<>();
  static {
    forbid("park",1); forbid("social_area",1); forbid("prohibited_site",1); forbid("water",1); forbid("oks",1);
    M.put("road",new RestrictionRule("road",true,1.5,45,1.60,0,3));
    M.put("tram_tracks",new RestrictionRule("tram_tracks",true,1.5,45,1.75,0,3));
    forbid("railway",1.5);
    M.put("gas_pipeline",new RestrictionRule("gas_pipeline",true,2,0,1.25,.2,2));
    M.put("power_cable",new RestrictionRule("power_cable",true,2,0,1.15,.5,2));
    M.put("heat_network",new RestrictionRule("heat_network",true,1,0,1.05,.5,2));
  }
  private static void forbid(String t,double c){M.put(t,new RestrictionRule(t,false,c,0,1,0,0));}
  public static RestrictionRule get(String type){return M.get(type);}
  public static boolean known(String type){return M.containsKey(type);}
  public static double objectHalfWidth(String type){return "gas_pipeline".equals(type)?.2:"power_cable".equals(type)?.1:0;}
}

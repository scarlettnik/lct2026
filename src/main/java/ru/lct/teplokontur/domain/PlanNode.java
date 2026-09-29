package ru.lct.teplokontur.domain;
import org.locationtech.jts.geom.Point;import java.util.*;
public class PlanNode {public final String id;public final NodeKind kind;public final Point point;public String oksId;public String existingObjectId;public String existingObjectType;public Integer existingDn;public double tieChainage=-1;public final List<PlanEdge> children=new ArrayList<>();public PlanEdge parentEdge;public PlanNode(String id,NodeKind kind,Point point){this.id=id;this.kind=kind;this.point=point;}public int degree(){return children.size()+(parentEdge==null?0:1);} }

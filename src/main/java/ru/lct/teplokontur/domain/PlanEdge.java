package ru.lct.teplokontur.domain;
import org.locationtech.jts.geom.LineString;
public class PlanEdge {public String id;public PlanNode parent,child;public LineString geometry;public double flow;public int dn;public int minDn;public double length,cost;public PlanEdge(String id,PlanNode parent,PlanNode child,LineString geometry){this.id=id;this.parent=parent;this.child=child;this.geometry=geometry;this.length=geometry.getLength();}}

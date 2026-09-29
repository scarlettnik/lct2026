package ru.lct.teplokontur.domain;import org.locationtech.jts.geom.LineString;
public class CostedRouteSegment {public LineString geometry;public String layingMethod;public double depthStart,depthEnd,length,cost,specialFactor;public int dn;public double flow;}

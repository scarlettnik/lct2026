package ru.lct.teplokontur.domain;
public final class RestrictionRule {
  public final String type; public final boolean crossingAllowed; public final double horizontalClearance,minCrossAngle,specialFactor,verticalClearance,extraEachSide;
  public RestrictionRule(String type,boolean crossingAllowed,double horizontalClearance,double minCrossAngle,double specialFactor,double verticalClearance,double extraEachSide){this.type=type;this.crossingAllowed=crossingAllowed;this.horizontalClearance=horizontalClearance;this.minCrossAngle=minCrossAngle;this.specialFactor=specialFactor;this.verticalClearance=verticalClearance;this.extraEachSide=extraEachSide;}
}

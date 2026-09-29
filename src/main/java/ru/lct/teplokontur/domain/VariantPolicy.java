package ru.lct.teplokontur.domain;
public enum VariantPolicy {
  OFFICIAL_BEST(1.0,1.0,1.0), INFRASTRUCTURE_LIGHT(1.0,1.35,1.25), FULL_COVERAGE(1.15,.9,.8);
  public final double unconnectedWeight,reconstructionWeight,tieWeight;
  VariantPolicy(double u,double r,double t){unconnectedWeight=u;reconstructionWeight=r;tieWeight=t;}
}

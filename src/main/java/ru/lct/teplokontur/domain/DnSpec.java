package ru.lct.teplokontur.domain;
public final class DnSpec {
  public final int dn; public final double capacity, maxLength, newCost, reconCost, outerDiameter, pairWidth, pairHeight;
  public DnSpec(int dn,double capacity,double maxLength,double newCost,double reconCost,double outerDiameter,double pairWidth,double pairHeight){
    this.dn=dn;this.capacity=capacity;this.maxLength=maxLength;this.newCost=newCost;this.reconCost=reconCost;this.outerDiameter=outerDiameter;this.pairWidth=pairWidth;this.pairHeight=pairHeight;
  }
}

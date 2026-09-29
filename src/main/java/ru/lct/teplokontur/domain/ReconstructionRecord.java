package ru.lct.teplokontur.domain;import org.locationtech.jts.geom.LineString;
public class ReconstructionRecord {public String existingObjectId;public double existingFlow,addedFlow,calculatedFlow,length,cost;public int existingDn,requiredDn;public LineString geometry;}

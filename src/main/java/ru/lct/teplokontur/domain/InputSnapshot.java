package ru.lct.teplokontur.domain;
import org.locationtech.jts.geom.*;import java.util.*;
public final class InputSnapshot {
 public final long scenarioId; public final List<ExistingEdge> network=new ArrayList<>(); public final List<ExistingChamber> chambers=new ArrayList<>(); public final List<Terminal> terminals=new ArrayList<>(); public final List<Restriction> restrictions=new ArrayList<>(); public final List<InputFeature> features=new ArrayList<>(); public Point source;
 public InputSnapshot(long scenarioId){this.scenarioId=scenarioId;}
 public static final class ExistingEdge {public String id,upstream;public LineString geometry;public int dn;public double flow;}
 public static final class ExistingChamber {public String id,upstream;public Point point;public int dn;}
 public static final class Terminal {public String oksId,pointId;public Point point;public double flow;}
 public static final class Restriction {public String id,type;public Geometry geometry;}
 public static final class InputFeature {public long sequence;public String id,objectType,restrictionType,propertiesJson,geometryJson;}
}

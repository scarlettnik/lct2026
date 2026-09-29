package ru.lct.teplokontur.ingest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;import org.locationtech.jts.geom.*;import java.util.*;
public final class GeoJsonGeometry {
 private static final GeometryFactory GF=new GeometryFactory(new PrecisionModel(),4326);
 private GeoJsonGeometry(){}
 public static Geometry parse(JsonNode g){if(g==null||g.isNull())return null;String t=g.path("type").asText();JsonNode c=g.get("coordinates");switch(t){case"Point":return GF.createPoint(coord(c));case"LineString":return GF.createLineString(coords(c));case"Polygon":return polygon(c);case"MultiPolygon":return multiPolygon(c);case"MultiLineString":return multiLineString(c);default:throw new IllegalArgumentException("Unsupported geometry "+t);}}
 private static Coordinate coord(JsonNode n){return new Coordinate(n.get(0).asDouble(),n.get(1).asDouble(),n.size()>2?n.get(2).asDouble():Double.NaN);}
 private static Coordinate[] coords(JsonNode n){Coordinate[] a=new Coordinate[n.size()];for(int i=0;i<a.length;i++)a[i]=coord(n.get(i));return a;}
 private static LinearRing ring(JsonNode n){Coordinate[] a=coords(n);if(!a[0].equals2D(a[a.length-1])){a=Arrays.copyOf(a,a.length+1);a[a.length-1]=new Coordinate(a[0]);}return GF.createLinearRing(a);}
 private static Polygon polygon(JsonNode c){LinearRing shell=ring(c.get(0));LinearRing[] holes=new LinearRing[Math.max(0,c.size()-1)];for(int i=1;i<c.size();i++)holes[i-1]=ring(c.get(i));return GF.createPolygon(shell,holes);}
 private static MultiPolygon multiPolygon(JsonNode c){Polygon[] p=new Polygon[c.size()];for(int i=0;i<p.length;i++)p[i]=polygon(c.get(i));return GF.createMultiPolygon(p);}
 private static MultiLineString multiLineString(JsonNode c){LineString[] a=new LineString[c.size()];for(int i=0;i<a.length;i++)a[i]=GF.createLineString(coords(c.get(i)));return GF.createMultiLineString(a);}
 public static ObjectNode write(Geometry g,ObjectMapper om){ObjectNode n=om.createObjectNode();if(g instanceof Point){n.put("type","Point");n.set("coordinates",coordNode(g.getCoordinate(),om));}else if(g instanceof LineString){n.put("type","LineString");n.set("coordinates",coordsNode(g.getCoordinates(),om));}else if(g instanceof Polygon){n.put("type","Polygon");ArrayNode a=om.createArrayNode();Polygon p=(Polygon)g;a.add(coordsNode(p.getExteriorRing().getCoordinates(),om));for(int i=0;i<p.getNumInteriorRing();i++)a.add(coordsNode(p.getInteriorRingN(i).getCoordinates(),om));n.set("coordinates",a);}else throw new IllegalArgumentException("Output geometry type "+g.getGeometryType());return n;}
 private static ArrayNode coordNode(Coordinate c,ObjectMapper om){ArrayNode a=om.createArrayNode();a.add(c.x);a.add(c.y);if(Double.isFinite(c.getZ()))a.add(c.getZ());return a;} private static ArrayNode coordsNode(Coordinate[] cs,ObjectMapper om){ArrayNode a=om.createArrayNode();for(Coordinate c:cs)a.add(coordNode(c,om));return a;}
}

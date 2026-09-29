package ru.lct.teplokontur.validation;
import com.fasterxml.jackson.core.*;import com.fasterxml.jackson.databind.*;import java.io.*;import java.nio.file.*;import java.util.*;
public class OutputGeoJsonValidator {
 private final ObjectMapper om;public OutputGeoJsonValidator(ObjectMapper om){this.om=om;}
 public List<String> validate(Path file)throws IOException{List<String> errors=new ArrayList<>();Map<String,Integer> summaries=new HashMap<>();Map<String,List<JsonNode>> variants=new HashMap<>();try(JsonParser p=om.getFactory().createParser(file.toFile())){if(p.nextToken()!=JsonToken.START_OBJECT){errors.add("Root is not object");return errors;}boolean fc=false,features=false;while(p.nextToken()!=JsonToken.END_OBJECT){String n=p.currentName();p.nextToken();if("type".equals(n)){fc="FeatureCollection".equals(p.getValueAsString());}else if("features".equals(n)){features=true;if(p.currentToken()!=JsonToken.START_ARRAY){errors.add("features is not array");p.skipChildren();continue;}while(p.nextToken()!=JsonToken.END_ARRAY){JsonNode f=om.readTree(p);checkFeature(f,errors,summaries);variants.computeIfAbsent(f.path("properties").path("variant_id").asText(),key->new ArrayList<>()).add(f);}}else p.skipChildren();}if(!fc)errors.add("Root type != FeatureCollection");if(!features)errors.add("features missing");}for(Map.Entry<String,Integer> e:summaries.entrySet())if(e.getValue()!=1)errors.add("variant "+e.getKey()+" has "+e.getValue()+" summaries");for(List<JsonNode> variant:variants.values())checkTotals(variant,errors);return errors;}
 private void checkFeature(JsonNode f,List<String> errors,Map<String,Integer> summaries){JsonNode pr=f.path("properties");String t=pr.path("object_type").asText();String v=pr.path("variant_id").asText();if(t.isEmpty()||v.isEmpty())errors.add("feature without object_type/variant_id");Set<String> req=new HashSet<>();if("heat_network".equals(t))req.addAll(Arrays.asList("id","start_node_id","end_node_id","flow_tph","diameter","length","laying_method","depth_start","depth_end","cost"));else if("tie_in".equals(t)||"heat_network_reconstruction".equals(t)||"heat_chamber_reconstruction".equals(t))errors.add("unsupported output object_type under current model: "+t);else if("heat_chamber".equals(t))req.addAll(Arrays.asList("id","diameter","cost"));else if("technical_node".equals(t))req.add("id");else if("variant_summary".equals(t)){summaries.merge(v,1,Integer::sum);req.addAll(Arrays.asList("id","rank","construction_cost","chamber_construction_cost","unconnected_penalty","calculated_cost","new_network_length","length","score","unconnected_oks_ids"));req.addAll(Arrays.asList("tie_in_cost","reconstruction_cost","chamber_reconstruction_cost","reconstruction_length"));}else errors.add("unexpected output object_type "+t);for(String x:req)if(!pr.has(x))errors.add(t+" missing "+x);checkCoordinates(f,errors);}
 private void checkTotals(List<JsonNode> features,List<String> errors){
  Map<String,Double> costs=new HashMap<>(),lengths=new HashMap<>();Set<String> ids=new HashSet<>();JsonNode summary=null;
  for(JsonNode feature:features){JsonNode p=feature.path("properties");String type=p.path("object_type").asText();if(!ids.add(p.path("id").asText()))errors.add("Duplicate output id");if("variant_summary".equals(type)){summary=p;continue;}costs.merge(type,p.path("cost").asDouble(),Double::sum);if("heat_network".equals(type))costs.merge("tie_in",p.path("tie_in_cost").asDouble(),Double::sum);lengths.merge(type,p.path("length").asDouble(),Double::sum);}
  if(summary==null){errors.add("Variant missing summary");return;}
  String[][] fields={{"heat_network","construction_cost"},{"heat_chamber","chamber_construction_cost"},{"tie_in","tie_in_cost"},{"heat_network_reconstruction","reconstruction_cost"},{"heat_chamber_reconstruction","chamber_reconstruction_cost"}};
  for(String[] field:fields)if(Math.abs(costs.getOrDefault(field[0],0.)-summary.path(field[1]).asDouble())>.1)errors.add("Object cost differs from summary: "+field[1]);
  double cost=costs.values().stream().mapToDouble(Double::doubleValue).sum()+summary.path("unconnected_penalty").asDouble();
  double length=lengths.values().stream().mapToDouble(Double::doubleValue).sum();
  if(Math.abs(cost-summary.path("calculated_cost").asDouble())>.1)errors.add("Total cost differs from exported objects");
  if(Math.abs(length-summary.path("length").asDouble())>.001)errors.add("Total length differs from exported objects");
  if(Math.abs(ru.lct.teplokontur.domain.RuleBook.officialScore(cost,length)-summary.path("score").asDouble())>1e-7)errors.add("Exported score mismatch");
 }
 private void checkCoordinates(JsonNode feature,List<String> errors){
  JsonNode geometry=feature.path("geometry"),properties=feature.path("properties");
  if(geometry.isNull())return;
  JsonNode coordinates=geometry.path("coordinates");
  if("Point".equals(geometry.path("type").asText())){position(coordinates,errors);return;}
  for(JsonNode coordinate:coordinates)position(coordinate,errors);
  if("heat_network".equals(properties.path("object_type").asText())&&coordinates.size()>0){
   boolean depth=properties.hasNonNull("depth_start")&&properties.hasNonNull("depth_end");
   for(JsonNode coordinate:coordinates)if(coordinate.size()!=(depth?3:2))errors.add("Wrong coordinate dimension for depth mode");
   if(depth&&(Math.abs(coordinates.get(0).path(2).asDouble()+properties.path("depth_start").asDouble())>1e-7||Math.abs(coordinates.get(coordinates.size()-1).path(2).asDouble()+properties.path("depth_end").asDouble())>1e-7))errors.add("XYZ depth differs from depth attributes");
  }
 }
 private void position(JsonNode coordinate,List<String> errors){if(!coordinate.isArray()||coordinate.size()<2||coordinate.size()>3){errors.add("Invalid position");return;}for(JsonNode number:coordinate)if(!number.isNumber()||!Double.isFinite(number.asDouble()))errors.add("Non-finite position");}
}

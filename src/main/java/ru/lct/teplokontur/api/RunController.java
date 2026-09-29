package ru.lct.teplokontur.api;
import com.fasterxml.jackson.core.*;import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;import org.springframework.core.io.*;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;import ru.lct.teplokontur.domain.RunMode;import ru.lct.teplokontur.persistence.RunEntity;import ru.lct.teplokontur.service.OptimizationService;import java.io.*;import java.nio.file.*;import java.util.*;
@RestController @RequestMapping("/api/v1")
public class RunController {private final OptimizationService svc;private final ObjectMapper om;public RunController(OptimizationService svc,ObjectMapper om){this.svc=svc;this.om=om;}
 @PostMapping("/scenarios/{sid}/runs") public Map<String,Object> start(@PathVariable long sid,@RequestParam(defaultValue="2d")String mode){RunMode m="3d".equalsIgnoreCase(mode)||"depth".equalsIgnoreCase(mode)?RunMode.DEPTH:RunMode.TWO_D;RunEntity r=svc.create(sid,m);svc.execute(r.id);return dto(r);}
 @GetMapping("/runs/{id}") public ResponseEntity<?> get(@PathVariable long id){return svc.get(id).<ResponseEntity<?>>map(r->ResponseEntity.ok(dto(r))).orElse(ResponseEntity.notFound().build());}
 @GetMapping("/runs/{id}/variants") public ResponseEntity<?> variants(@PathVariable long id)throws Exception{return svc.get(id).<ResponseEntity<?>>map(r->{try{return ResponseEntity.ok(r.summaryJson==null?om.createArrayNode():om.readTree(r.summaryJson));}catch(Exception e){return ResponseEntity.internalServerError().body(e.getMessage());}}).orElse(ResponseEntity.notFound().build());}
 @GetMapping("/runs/{id}/variants/{variantId}") public ResponseEntity<?> variant(@PathVariable long id,@PathVariable String variantId){return variantNode(id,variantId,false);}
 @GetMapping("/runs/{id}/variants/{variantId}/validation") public ResponseEntity<?> variantValidation(@PathVariable long id,@PathVariable String variantId){return variantNode(id,variantId,true);}
 @GetMapping(value="/runs/{id}/variants/{variantId}/export",produces="application/geo+json")
 public ResponseEntity<StreamingResponseBody> exportVariant(@PathVariable long id,@PathVariable String variantId) {
   Optional<RunEntity> run=svc.get(id);
   if(run.isEmpty()||run.get().resultPath==null||run.get().summaryJson==null)return ResponseEntity.notFound().build();
   try {
     boolean exists=false;
     for(JsonNode variant:om.readTree(run.get().summaryJson))if(variantId.equals(variant.path("variant_id").asText()))exists=true;
     if(!exists||!Files.isRegularFile(Paths.get(run.get().resultPath)))return ResponseEntity.notFound().build();
     Path source=Paths.get(run.get().resultPath);
     StreamingResponseBody body=output->writeVariant(source,variantId,output);
     return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/geo+json"))
         .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
             .filename("teplokontur-run-"+id+"-"+variantId+".geojson").build().toString()).body(body);
   } catch(IOException ex){return ResponseEntity.internalServerError().build();}
 }
 private void writeVariant(Path source,String variantId,OutputStream output)throws IOException {
   JsonFactory factory=om.getFactory();
   try(JsonParser parser=factory.createParser(source.toFile());JsonGenerator generator=factory.createGenerator(output)) {
     if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Invalid result FeatureCollection");
     generator.writeStartObject();
     while(parser.nextToken()!=JsonToken.END_OBJECT) {
       if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new IOException("Invalid result FeatureCollection");
       String field=parser.currentName();JsonToken value=parser.nextToken();
       if("features".equals(field)) {
         if(value!=JsonToken.START_ARRAY)throw new IOException("Invalid result features array");
         generator.writeArrayFieldStart(field);
         while(parser.nextToken()!=JsonToken.END_ARRAY) {
           JsonNode feature=om.readTree(parser);
           if(variantId.equals(feature.path("properties").path("variant_id").asText()))om.writeTree(generator,feature);
         }
         generator.writeEndArray();
       } else {generator.writeFieldName(field);om.writeTree(generator,om.readTree(parser));}
     }
     generator.writeEndObject();generator.flush();
   }
 }
 private ResponseEntity<?> variantNode(long id,String variantId,boolean validationOnly){Optional<RunEntity> o=svc.get(id);if(o.isEmpty()||o.get().summaryJson==null)return ResponseEntity.notFound().build();try{JsonNode a=om.readTree(o.get().summaryJson);for(JsonNode x:a)if(variantId.equals(x.path("variant_id").asText())){if(!validationOnly)return ResponseEntity.ok(x);ObjectNode v=om.createObjectNode();v.put("variant_id",variantId);v.put("passed",x.path("validation_passed").asBoolean());v.set("errors",x.path("validation_errors"));v.set("warnings",x.path("validation_warnings"));return ResponseEntity.ok(v);}return ResponseEntity.notFound().build();}catch(Exception e){return ResponseEntity.internalServerError().body(e.getMessage());}}
 @GetMapping("/runs/{id}/export") public ResponseEntity<Resource> export(@PathVariable long id){Optional<RunEntity> o=svc.get(id);if(o.isEmpty()||o.get().resultPath==null)return ResponseEntity.notFound().build();Path p=Paths.get(o.get().resultPath);if(!Files.exists(p))return ResponseEntity.notFound().build();Resource r=new FileSystemResource(p);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/geo+json")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=teplokontur-run-"+id+".geojson").body(r);}
 private Map<String,Object> dto(RunEntity r){Map<String,Object> m=new LinkedHashMap<>();m.put("run_id",r.id);m.put("scenario_id",r.scenarioId);m.put("mode",r.mode);m.put("status",r.status);m.put("phase",r.phase);m.put("progress",r.progress);m.put("error",r.error);return m;}}

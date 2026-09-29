package ru.lct.teplokontur.api;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import ru.lct.teplokontur.ingest.GeoJsonStreamingIngestor;import ru.lct.teplokontur.persistence.*;import java.io.*;import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.apache.tomcat.util.http.fileupload.*;
import org.apache.tomcat.util.http.fileupload.servlet.ServletRequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
@RestController @RequestMapping("/api/v1/scenarios")
@Tag(name="Сценарии", description="Загрузка исходных GeoJSON-данных и проверка состояния сценария")
public class ScenarioController {private final GeoJsonStreamingIngestor ing;private final ScenarioRepository repo;public ScenarioController(GeoJsonStreamingIngestor ing,ScenarioRepository repo){this.ing=ing;this.repo=repo;}
 @Operation(summary="Загрузить сценарий файлом", description="Принимает один файл GeoJSON в multipart-поле file и создаёт сценарий для последующей оптимизации.")
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> upload(HttpServletRequest request)throws IOException,FileUploadException {
   FileUpload upload=new FileUpload();
   upload.setFileSizeMax(GeoJsonStreamingIngestor.MAX_UPLOAD_BYTES);
   upload.setSizeMax(GeoJsonStreamingIngestor.MAX_UPLOAD_BYTES+65536);
   FileItemIterator parts=upload.getItemIterator(new ServletRequestContext(request));
   ScenarioEntity scenario=null;
   while(parts.hasNext()) {
     FileItemStream part=parts.next();
     if(!"file".equals(part.getFieldName())||part.isFormField()||scenario!=null)
       throw new IllegalArgumentException("Expected exactly one file part named file");
     try(InputStream input=part.openStream()){scenario=ing.ingest(input,part.getName());}
   }
   if(scenario==null)throw new IllegalArgumentException("file part missing");
   return dto(scenario);
 }
 @Operation(summary="Загрузить сценарий в теле запроса", description="Принимает GeoJSON, JSON или двоичные данные в теле запроса. Имя файла можно передать в заголовке X-Filename.")
 @PostMapping(consumes={"application/geo+json",MediaType.APPLICATION_JSON_VALUE,MediaType.APPLICATION_OCTET_STREAM_VALUE})
 public Map<String,Object> uploadRaw(HttpServletRequest request,
     @RequestHeader(value="X-Filename",required=false) String name)throws IOException {
   if(request.getContentLengthLong()>GeoJsonStreamingIngestor.MAX_UPLOAD_BYTES)
     throw new IllegalArgumentException("Upload exceeds 3 GiB");
   return dto(ing.ingest(request.getInputStream(),name));
 }
 @Operation(summary="Получить состояние сценария", description="Возвращает статус обработки, число объектов и диагностические сведения загруженного сценария.")
 @GetMapping("/{id}/validation") public ResponseEntity<?> validation(@PathVariable long id){return repo.findById(id).<ResponseEntity<?>>map(s->ResponseEntity.ok(dto(s))).orElse(ResponseEntity.notFound().build());}
 private Map<String,Object> dto(ScenarioEntity s){Map<String,Object> m=new LinkedHashMap<>();m.put("scenario_id",s.id);m.put("name",s.name);m.put("status",s.status);m.put("feature_count",s.featureCount);m.put("diagnostic",s.diagnostic);return m;}}

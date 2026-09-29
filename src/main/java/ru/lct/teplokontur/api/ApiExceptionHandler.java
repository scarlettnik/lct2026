package ru.lct.teplokontur.api;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestControllerAdvice
public class ApiExceptionHandler {
 @ExceptionHandler({java.io.IOException.class,org.apache.tomcat.util.http.fileupload.FileUploadException.class})
 public ResponseEntity<Map<String,Object>> upload(Exception e){Map<String,Object> m=new LinkedHashMap<>();m.put("error","INPUT_VALIDATION_ERROR");m.put("message",e.getMessage());return ResponseEntity.unprocessableEntity().body(m);}
 @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
 public ResponseEntity<Map<String,Object>> duplicate(org.springframework.dao.DataIntegrityViolationException e){Map<String,Object> m=new LinkedHashMap<>();m.put("error","INPUT_VALIDATION_ERROR");m.put("message","Feature IDs must be unique within a scenario and fit the database fields");return ResponseEntity.unprocessableEntity().body(m);}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,Object>> bad(IllegalArgumentException e){Map<String,Object> m=new LinkedHashMap<>();m.put("error","INPUT_VALIDATION_ERROR");m.put("message",e.getMessage());return ResponseEntity.unprocessableEntity().body(m);}
 @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> fail(Exception e){Map<String,Object> m=new LinkedHashMap<>();m.put("error","INTERNAL_ERROR");m.put("message",e.getMessage());return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(m);}
}

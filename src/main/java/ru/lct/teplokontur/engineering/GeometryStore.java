package ru.lct.teplokontur.engineering;
import org.locationtech.jts.geom.*;import org.locationtech.jts.io.WKBReader;import org.springframework.stereotype.Component;import ru.lct.teplokontur.persistence.FeatureEntity;
@Component
public class GeometryStore {private final WKBReader r=new WKBReader();public Geometry read(FeatureEntity e){try{return r.read(e.geometryWkb);}catch(Exception x){throw new IllegalStateException(x);}}}

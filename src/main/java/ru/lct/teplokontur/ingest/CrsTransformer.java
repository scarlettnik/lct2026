package ru.lct.teplokontur.ingest;
import org.locationtech.jts.geom.*;import org.locationtech.proj4j.*;import org.springframework.stereotype.Component;
@Component
public class CrsTransformer {
 private final CoordinateTransform fwd,inv;
 public CrsTransformer(){CRSFactory c=new CRSFactory();CoordinateTransformFactory f=new CoordinateTransformFactory();CoordinateReferenceSystem w=c.createFromParameters("WGS84","+proj=longlat +datum=WGS84 +no_defs"),u=c.createFromParameters("UTM37N","+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");fwd=f.createTransform(w,u);inv=f.createTransform(u,w);}
 public Geometry toUtm(Geometry g){return transform(g,fwd);} public Geometry toWgs(Geometry g){return transform(g,inv);}
 private Geometry transform(Geometry g,CoordinateTransform t){Geometry x=(Geometry)g.copy();x.apply((CoordinateFilter)c->{ProjCoordinate a=new ProjCoordinate(c.x,c.y),b=new ProjCoordinate();t.transform(a,b);c.x=b.x;c.y=b.y;});x.geometryChanged();return x;}
}

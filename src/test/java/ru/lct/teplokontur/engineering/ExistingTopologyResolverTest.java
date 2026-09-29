package ru.lct.teplokontur.engineering;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.lct.teplokontur.domain.InputSnapshot;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExistingTopologyResolverTest {
    private final GeometryFactory gf=new GeometryFactory();
    @Test void reconstructsUpstreamInEitherGeometryDirectionAndInfersChamberDiameter() {
        InputSnapshot snapshot=new InputSnapshot(1);snapshot.source=point(0,0);
        InputSnapshot.ExistingEdge upstream=pipe("upstream",100,0,300);
        InputSnapshot.ExistingEdge branch=pipe("branch",100,200,150);
        snapshot.network.addAll(Arrays.asList(upstream,branch));
        InputSnapshot.ExistingChamber chamber=new InputSnapshot.ExistingChamber();chamber.id="junction";chamber.point=point(100,0);chamber.dn=200;snapshot.chambers.add(chamber);
        new ExistingTopologyResolver().resolve(snapshot,"plant",Collections.singleton(chamber.id));
        assertEquals("plant",upstream.upstream);assertEquals(chamber.id,branch.upstream);
        assertEquals(upstream.id,chamber.upstream);assertEquals(300,chamber.dn);
    }
    @Test void refusesToInventAConnectionForAnIsolatedPipe() {
        InputSnapshot snapshot=new InputSnapshot(2);snapshot.source=point(0,0);snapshot.network.add(pipe("isolated",100,200,150));
        assertThrows(IllegalArgumentException.class,()->new ExistingTopologyResolver().resolve(snapshot,"plant",Collections.emptySet()));
    }
    private InputSnapshot.ExistingEdge pipe(String id,double start,double end,int dn){InputSnapshot.ExistingEdge e=new InputSnapshot.ExistingEdge();e.id=id;e.dn=dn;e.geometry=gf.createLineString(new Coordinate[]{new Coordinate(start,0),new Coordinate(end,0)});return e;}
    private Point point(double x,double y){return gf.createPoint(new Coordinate(x,y));}
}

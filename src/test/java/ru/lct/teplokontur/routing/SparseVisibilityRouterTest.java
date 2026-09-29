package ru.lct.teplokontur.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.RunMode;

class SparseVisibilityRouterTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void returnsTheExactDirectSegmentWhenItIsLegal() {
        Point start = point(0, 0);
        Point goal = point(100, 0);
        LineString route = new SparseVisibilityRouter().route(new InputSnapshot(1), start, goal, 100,
                RunMode.TWO_D, Collections.emptyList(), 0L).orElseThrow();

        assertTrue(route.equalsExact(line(0, 0, 100, 0)));
    }

    @Test
    void detoursAroundForbiddenGeometryWithoutRasterisingTheEnvelope() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Restriction block = new InputSnapshot.Restriction();
        block.id = "closed";
        block.type = "park";
        block.geometry = geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(40, -10), new Coordinate(60, -10), new Coordinate(60, 10),
                new Coordinate(40, 10), new Coordinate(40, -10)});
        snapshot.restrictions.add(block);

        LineString route = new SparseVisibilityRouter().route(snapshot, point(0, 0), point(100, 0), 100,
                RunMode.TWO_D, Collections.emptyList(), 0L).orElseThrow();

        assertTrue(route.getLength() > 100.0);
        assertTrue(route.disjoint(block.geometry.buffer(1.255)));
    }

    @Test
    void expandsTheExactVisibilityCorridorForALongBarrier() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Restriction barrier = new InputSnapshot.Restriction();
        barrier.id = "long-closed";
        barrier.type = "park";
        barrier.geometry = geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(40, -220), new Coordinate(60, -220), new Coordinate(60, 220),
                new Coordinate(40, 220), new Coordinate(40, -220)});
        snapshot.restrictions.add(barrier);

        LineString route = new SparseVisibilityRouter().route(snapshot, point(0, 0), point(100, 0), 100,
                RunMode.TWO_D, Collections.emptyList(), 0L).orElseThrow();

        assertTrue(route.getLength() > 450.0);
        assertTrue(route.disjoint(barrier.geometry.buffer(1.255)));
        assertTrue(java.util.Arrays.stream(route.getCoordinates()).anyMatch(point -> Math.abs(point.y) > 120.0));
    }

    @Test
    void reusesOneDnAwareVisibilityGraphForSeveralRouteSearches() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Restriction block = new InputSnapshot.Restriction();
        block.id = "shared-template";
        block.type = "park";
        block.geometry = geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(40, -20), new Coordinate(60, -20), new Coordinate(60, 20),
                new Coordinate(40, 20), new Coordinate(40, -20)});
        snapshot.restrictions.add(block);
        SparseVisibilityRouter router = new SparseVisibilityRouter();

        assertTrue(router.route(snapshot, point(0, 0), point(100, 0), 100,
                RunMode.TWO_D, Collections.emptyList(), 1L).isPresent());
        assertTrue(router.route(snapshot, point(0, 10), point(100, 10), 100,
                RunMode.TWO_D, Collections.emptyList(), 2L).isPresent());

        assertTrue(router.staticGraphBuildCount() == 1,
                "one visibility graph must be shared by all searches in one scenario");
    }

    private Point point(double x, double y) { return geometryFactory.createPoint(new Coordinate(x, y)); }

    @Test
    void goesAroundTheBuildingToReachTheNearestFacadeInBothModes() {
        InputSnapshot snapshot=new InputSnapshot(1);
        InputSnapshot.Restriction building=new InputSnapshot.Restriction();
        building.id="building";building.type="oks_existing";
        building.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(0,0),
                new Coordinate(30,0),new Coordinate(30,20),new Coordinate(0,20),new Coordinate(0,0)});
        snapshot.restrictions.add(building);
        for(RunMode mode:RunMode.values()) {
            LineString route=new SparseVisibilityRouter().route(snapshot,point(-20,10),point(28,10),100,
                    mode,Collections.emptyList(),0).orElseThrow();
            assertEquals(2,route.intersection(building.geometry).getLength(),1e-5);
            assertTrue(route.getLength()>48,"Must route around the building instead of taking the invalid direct line");
            Coordinate[] c=route.getCoordinates();
            assertEquals(10,c[c.length-2].y,1e-5);
            assertTrue(c[c.length-2].x>30,"Final lead approaches the east wall");
            for(int i=1;i<c.length;i++)assertTrue(new RouteConstraintEngine(snapshot).assess(
                    geometryFactory.createLineString(new Coordinate[]{c[i-1],c[i]}),100,Collections.emptyList(),
                    null,mode,i>=c.length-2?c[c.length-1]:null,i==c.length-2?c[c.length-2]:null).feasible);
        }
    }

    @Test
    void canUseEitherEquallyNearestWallWhenOneApproachIsBlocked() {
        InputSnapshot snapshot=new InputSnapshot(1);
        InputSnapshot.Restriction building=new InputSnapshot.Restriction();
        building.id="building";building.type="oks_existing";
        building.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(0,0),
                new Coordinate(30,0),new Coordinate(30,20),new Coordinate(0,20),new Coordinate(0,0)});
        InputSnapshot.Restriction blocked=new InputSnapshot.Restriction();
        blocked.id="blocked-approach";blocked.type="park";
        blocked.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(5,-20),
                new Coordinate(25,-20),new Coordinate(25,-1),new Coordinate(5,-1),new Coordinate(5,-20)});
        snapshot.restrictions.add(building);snapshot.restrictions.add(blocked);
        LineString route=new SparseVisibilityRouter().route(snapshot,point(-20,10),point(15,10),100,
                RunMode.TWO_D,Collections.emptyList(),0).orElseThrow();
        assertEquals(10,route.intersection(building.geometry).getLength(),1e-5);
        assertTrue(route.getCoordinateN(route.getNumPoints()-2).y>20);
        assertTrue(route.disjoint(blocked.geometry));
    }

    @Test
    void fallsBackToTheNearestReachableFacadeWhenTheGeometricNearestIsBlocked() {
        InputSnapshot snapshot=new InputSnapshot(1);
        InputSnapshot.Restriction building=new InputSnapshot.Restriction();
        building.id="building";building.type="oks_existing";
        building.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(0,0),
                new Coordinate(30,0),new Coordinate(30,20),new Coordinate(0,20),new Coordinate(0,0)});
        InputSnapshot.Restriction blocked=new InputSnapshot.Restriction();
        blocked.id="blocked-nearest-wall";blocked.type="park";
        blocked.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(31,4),
                new Coordinate(42,4),new Coordinate(42,16),new Coordinate(31,16),new Coordinate(31,4)});
        snapshot.restrictions.add(building);snapshot.restrictions.add(blocked);

        for(RunMode mode:RunMode.values()) {
            LineString route=new SparseVisibilityRouter().route(snapshot,point(-20,30),point(28,10),100,
                    mode,Collections.emptyList(),0).orElseThrow();
            assertEquals(10,route.intersection(building.geometry).getLength(),1e-5,
                    "East wall is only 2 m away but blocked; the next reachable north/south wall is 10 m away");
            Coordinate port=route.getCoordinateN(route.getNumPoints()-2);
            assertTrue(port.y>20||port.y<0,"Fallback must use another exterior wall");
            assertTrue(route.disjoint(blocked.geometry));
        }
    }

    @Test
    void cachedRouteStillRespectsNewlyBuiltLines() {
        InputSnapshot snapshot=new InputSnapshot(1);
        SparseVisibilityRouter router=new SparseVisibilityRouter();
        assertTrue(router.route(snapshot,point(0,0),point(100,0),100,
                RunMode.TWO_D,Collections.emptyList(),0).isPresent());
        LineString occupied=line(50,-20,50,20);
        var result=router.route(snapshot,point(0,0),point(100,0),100,
                RunMode.TWO_D,Collections.singletonList(occupied),0);
        assertTrue(result.isEmpty()||result.get().disjoint(occupied));
    }

    @Test
    void entersNarrowNotchAtNearestWallWithoutReenteringAnotherWing() {
        InputSnapshot snapshot=new InputSnapshot(1);
        InputSnapshot.Restriction building=new InputSnapshot.Restriction();
        building.id="narrow-courtyard";building.type="oks_existing";
        building.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(0,0),
                new Coordinate(30,0),new Coordinate(30,30),new Coordinate(14,30),new Coordinate(14,5),
                new Coordinate(10,5),new Coordinate(10,30),new Coordinate(0,30),new Coordinate(0,0)});
        snapshot.restrictions.add(building);
        assertEquals(2,building.geometry.getBoundary().distance(point(8,10)),1e-9);
        for(RunMode mode:RunMode.values()) {
            LineString route=new SparseVisibilityRouter().route(snapshot,point(-20,10),point(8,10),100,
                    mode,Collections.emptyList(),0).orElseThrow();
            assertEquals(2,route.intersection(building.geometry).getLength(),1e-5);
            Coordinate port=route.getCoordinateN(route.getNumPoints()-2);
            assertTrue(port.x>10&&port.x<14,"The exterior turn must fit inside the notch");
        }
    }
    private LineString line(double... values) {
        Coordinate[] coordinates = new Coordinate[values.length / 2];
        for (int i = 0; i < values.length; i += 2) coordinates[i / 2] = new Coordinate(values[i], values[i + 1]);
        return geometryFactory.createLineString(coordinates);
    }
}

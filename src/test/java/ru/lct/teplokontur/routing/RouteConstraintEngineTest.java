package ru.lct.teplokontur.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.RunMode;

class RouteConstraintEngineTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void preparesEachForbiddenRestrictionOnlyOncePerDiameter() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Restriction park = new InputSnapshot.Restriction();
        park.id = "park";
        park.type = "park";
        park.geometry = geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(40, -10), new Coordinate(60, -10), new Coordinate(60, 10),
                new Coordinate(40, 10), new Coordinate(40, -10)});
        snapshot.restrictions.add(park);
        RouteConstraintEngine constraints = new RouteConstraintEngine(snapshot);

        assertTrue(constraints.assess(line(0, 12, 100, 12), 100, Collections.emptyList(), null, RunMode.TWO_D).feasible);
        assertTrue(constraints.assess(line(0, -12, 100, -12), 100, Collections.emptyList(), null, RunMode.TWO_D).feasible);
        assertEquals(1, constraints.cachedForbiddenGeometryCount());

        assertFalse(constraints.assess(line(0, 0, 100, 0), 100, Collections.emptyList(), null, RunMode.TWO_D).feasible);
        assertEquals(1, constraints.cachedForbiddenGeometryCount());
        assertTrue(constraints.assess(line(0, 12, 100, 12), 200, Collections.emptyList(), null, RunMode.TWO_D).feasible);
        assertEquals(2, constraints.cachedForbiddenGeometryCount());
    }

    @Test
    void preservesTheSpecialMultiplierForAnAllowedCrossing() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Restriction road = new InputSnapshot.Restriction();
        road.id = "road";
        road.type = "road";
        road.geometry = line(50, -50, 50, 50);
        snapshot.restrictions.add(road);

        RouteAssessment crossing = new RouteConstraintEngine(snapshot).assess(
                line(0, 0, 100, 0), 100, Collections.emptyList(), null, RunMode.TWO_D);

        assertTrue(crossing.feasible);
        assertEquals(1.60, crossing.multiplier, 1e-9);
    }

    @Test
    void permitsOnlyTheTerminalLeadOutOfItsOwnBuilding() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Terminal terminal = new InputSnapshot.Terminal();
        terminal.point = geometryFactory.createPoint(new Coordinate(5, 5));
        snapshot.terminals.add(terminal);
        InputSnapshot.Restriction building = new InputSnapshot.Restriction();
        building.id = "own-building";
        building.type = "oks_existing";
        building.geometry = geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(10, 10),
                new Coordinate(0, 10), new Coordinate(0, 0)});
        snapshot.restrictions.add(building);
        RouteConstraintEngine constraints = new RouteConstraintEngine(snapshot);

        assertTrue(constraints.assess(line(5, 5, 30, 5), 100, Collections.emptyList(), null, RunMode.TWO_D,
                terminal.point.getCoordinate()).feasible);
        assertFalse(constraints.assess(line(-20, 5, 30, 5), 100, Collections.emptyList(), null, RunMode.TWO_D,
                terminal.point.getCoordinate()).feasible);
        InputSnapshot.Terminal other=new InputSnapshot.Terminal();other.point=geometryFactory.createPoint(new Coordinate(-5,5));snapshot.terminals.add(other);
        assertFalse(constraints.assess(line(-5,5,30,5),100,Collections.emptyList(),null,RunMode.TWO_D,
                other.point.getCoordinate()).feasible);
    }

    private LineString line(double... values) {
        Coordinate[] coordinates = new Coordinate[values.length / 2];
        for (int index = 0; index < values.length; index += 2) {
            coordinates[index / 2] = new Coordinate(values[index], values[index + 1]);
        }
        return geometryFactory.createLineString(coordinates);
    }

    @Test
    void rejectsFarWallAndObliqueEntryEvenForTheCorrectTerminal() {
        InputSnapshot snapshot = new InputSnapshot(1);
        InputSnapshot.Restriction building = new InputSnapshot.Restriction();
        building.id = "asymmetric-building";
        building.type = "oks_existing";
        building.geometry = geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(0,0), new Coordinate(30,0), new Coordinate(30,20),
                new Coordinate(0,20), new Coordinate(0,0)});
        snapshot.restrictions.add(building);
        Coordinate terminal = new Coordinate(28,10);
        RouteConstraintEngine constraints = new RouteConstraintEngine(snapshot);
        for (RunMode mode : RunMode.values()) {
            assertFalse(constraints.assess(line(-20,10,28,10),100,Collections.emptyList(),null,mode,terminal).feasible,
                    "Cannot travel 28 m under the building when the nearest wall is 2 m away");
            assertFalse(constraints.assess(line(40,14,28,10),100,Collections.emptyList(),null,mode,terminal).feasible,
                    "Must reach the nearest boundary point, not an arbitrary point on the nearest wall");
            assertTrue(constraints.assess(line(40,10,28,10),100,Collections.emptyList(),null,mode,terminal).feasible);
        }
    }

    @Test
    void acceptsAnExplicitFallbackWallButStillRejectsItWithoutFallbackSelection() {
        InputSnapshot snapshot=new InputSnapshot(1);
        InputSnapshot.Restriction building=new InputSnapshot.Restriction();
        building.id="fallback-building";building.type="oks_existing";
        building.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(0,0),
                new Coordinate(30,0),new Coordinate(30,20),new Coordinate(0,20),new Coordinate(0,0)});
        snapshot.restrictions.add(building);
        Coordinate terminal=new Coordinate(28,10);
        Coordinate northPort=new Coordinate(28,26);
        RouteConstraintEngine constraints=new RouteConstraintEngine(snapshot);

        for(RunMode mode:RunMode.values()) {
            LineString fallback=line(28,26,28,10);
            assertFalse(constraints.assess(fallback,100,Collections.emptyList(),null,mode,terminal).feasible,
                    "A farther wall must not be accepted unless nearer walls were proved unreachable");
            assertTrue(constraints.assess(fallback,100,Collections.emptyList(),null,mode,terminal,northPort).feasible,
                    "The explicitly selected next-nearest wall must be accepted when its lead is straight");
            assertTrue(constraints.assess(line(20,26,28,10),100,Collections.emptyList(),null,mode,terminal,
                    new Coordinate(20,26)).feasible,"A selected straight fallback lead need not be perpendicular to its wall");
        }
    }

    @Test
    void usesTheExternalFacadeInsteadOfAClosedCourtyardHole() {
        InputSnapshot snapshot=new InputSnapshot(1);
        InputSnapshot.Restriction building=new InputSnapshot.Restriction();building.id="courtyard";building.type="oks_existing";
        var exterior=geometryFactory.createLinearRing(new Coordinate[]{new Coordinate(0,0),new Coordinate(30,0),
                new Coordinate(30,20),new Coordinate(0,20),new Coordinate(0,0)});
        var hole=geometryFactory.createLinearRing(new Coordinate[]{new Coordinate(5,5),new Coordinate(25,5),
                new Coordinate(25,15),new Coordinate(5,15),new Coordinate(5,5)});
        building.geometry=geometryFactory.createPolygon(exterior,new org.locationtech.jts.geom.LinearRing[]{hole});
        snapshot.restrictions.add(building);Coordinate terminal=new Coordinate(26,10);
        RouteConstraintEngine constraints=new RouteConstraintEngine(snapshot);
        assertTrue(constraints.assess(line(40,10,26,10),100,Collections.emptyList(),null,RunMode.TWO_D,terminal).feasible);
        assertFalse(constraints.assess(line(20,10,26,10),100,Collections.emptyList(),null,RunMode.TWO_D,terminal).feasible);
    }

    @Test
    void permitsOneExteriorApproachButNoTransitOrSecondTurnInsideSetback() {
        InputSnapshot snapshot=new InputSnapshot(1);
        InputSnapshot.Restriction building=new InputSnapshot.Restriction();building.id="notch";building.type="oks_existing";
        building.geometry=geometryFactory.createPolygon(new Coordinate[]{new Coordinate(0,0),new Coordinate(30,0),
                new Coordinate(30,30),new Coordinate(14,30),new Coordinate(14,5),new Coordinate(10,5),
                new Coordinate(10,30),new Coordinate(0,30),new Coordinate(0,0)});
        snapshot.restrictions.add(building);RouteConstraintEngine constraints=new RouteConstraintEngine(snapshot);
        Coordinate terminal=new Coordinate(8,10),port=new Coordinate(10.5,10);
        assertTrue(constraints.assess(line(10.5,40,10.5,10),100,Collections.emptyList(),null,RunMode.TWO_D,terminal,port).feasible);
        assertFalse(constraints.assess(line(10.5,25,10.5,10),100,Collections.emptyList(),null,RunMode.TWO_D,terminal,port).feasible);
        assertFalse(constraints.assess(line(35,10,10.5,10),100,Collections.emptyList(),null,RunMode.TWO_D,terminal,port).feasible);
        assertFalse(constraints.assess(line(10.5,40,10.5,10),100,Collections.emptyList(),null,RunMode.TWO_D).feasible);
    }
}

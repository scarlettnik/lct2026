package ru.lct.teplokontur.ingest;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrsTransformerTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void transformsWgs84ToUtm37AndBackWithoutExternalEpsgFiles() {
        Point wgs84 = geometryFactory.createPoint(new Coordinate(37.6173, 55.7558));

        CrsTransformer transformer = new CrsTransformer();
        Point utm = (Point) transformer.toUtm(wgs84);
        Point restored = (Point) transformer.toWgs(utm);

        assertTrue(utm.getX() > 300_000 && utm.getX() < 500_000);
        assertTrue(utm.getY() > 6_000_000 && utm.getY() < 6_500_000);
        assertEquals(wgs84.getX(), restored.getX(), 0.000001);
        assertEquals(wgs84.getY(), restored.getY(), 0.000001);
    }
}

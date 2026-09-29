package ru.lct.teplokontur.domain;

import java.util.Arrays;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.LineString;

/** Immutable, exact XY coordinates and calculation parameters; no rounding. */
public final class LineCalculationKey {
    private final long[] values;
    private final int hash;

    public LineCalculationKey(LineString line, double... parameters) {
        CoordinateSequence points = line.getCoordinateSequence();
        values = new long[1 + points.size() * 2 + parameters.length];
        values[0] = points.size();
        for (int i = 0; i < points.size(); i++) {
            values[1 + i * 2] = Double.doubleToLongBits(points.getX(i));
            values[2 + i * 2] = Double.doubleToLongBits(points.getY(i));
        }
        for (int i = 0; i < parameters.length; i++)
            values[1 + points.size() * 2 + i] = Double.doubleToLongBits(parameters[i]);
        hash = Arrays.hashCode(values);
    }

    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return other instanceof LineCalculationKey
                && Arrays.equals(values, ((LineCalculationKey) other).values);
    }
}

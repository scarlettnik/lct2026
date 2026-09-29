package ru.lct.teplokontur.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression for binary x/y selector bounds. */
class JointChamberMilpTest {
    @Test
    void returnsRankedDistinctGlobalAssignmentsRatherThanIndependentMoves() {
        Map<Integer, Integer> candidateCounts = new LinkedHashMap<>();
        candidateCounts.put(0, 1);
        candidateCounts.put(1, 2);
        candidateCounts.put(2, 2);

        Map<JointChamberMilp.Edge, double[][]> costs = new LinkedHashMap<>();
        costs.put(new JointChamberMilp.Edge(0, 1), new double[][] {{9.0, 1.0}});
        costs.put(new JointChamberMilp.Edge(0, 2), new double[][] {{5.0, 2.0}});

        List<JointChamberMilp.Assignment> portfolio = new JointChamberMilp().solve(
                0, candidateCounts, costs, 4);

        assertEquals(4, portfolio.size());
        assertEquals(3.0, portfolio.get(0).cost(), 1e-9);
        assertEquals(Integer.valueOf(1), portfolio.get(0).selected().get(1));
        assertEquals(Integer.valueOf(1), portfolio.get(0).selected().get(2));
        assertEquals(6.0, portfolio.get(1).cost(), 1e-9);
        assertFalse(portfolio.get(0).selected().equals(portfolio.get(1).selected()));
    }

    @Test
    void excludesInfeasibleYVariablesWithinMilpBounds() {
        Map<Integer, Integer> candidateCounts = new LinkedHashMap<>();
        candidateCounts.put(0, 1);
        candidateCounts.put(1, 2);
        Map<JointChamberMilp.Edge, double[][]> costs = new LinkedHashMap<>();
        costs.put(new JointChamberMilp.Edge(0, 1), new double[][] {{Double.POSITIVE_INFINITY, 7.0}});

        List<JointChamberMilp.Assignment> portfolio = new JointChamberMilp().solve(
                0, candidateCounts, costs, 8);

        assertEquals(1, portfolio.size());
        assertEquals(Integer.valueOf(1), portfolio.get(0).selected().get(1));
        assertEquals(7.0, portfolio.get(0).cost(), 1e-9);
    }
}

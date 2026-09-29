package ru.lct.teplokontur.optimization;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.domain.VariantPolicy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilpPortfolioSelectorTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void selectsThreeDifferentRoutesWithoutArtificialPolicyExclusion() {
        NetworkPlan sharedOfficial = candidate(VariantPolicy.OFFICIAL_BEST, 1.0,
                new Coordinate(0, 0), new Coordinate(50, 0), new Coordinate(100, 0));
        NetworkPlan sharedLight = candidate(VariantPolicy.INFRASTRUCTURE_LIGHT, 1.1,
                new Coordinate(0, 0), new Coordinate(50, 0), new Coordinate(100, 0));
        NetworkPlan sharedCoverage = candidate(VariantPolicy.FULL_COVERAGE, 1.2,
                new Coordinate(0, 0), new Coordinate(50, 0), new Coordinate(100, 0));
        NetworkPlan north = candidate(VariantPolicy.OFFICIAL_BEST, 1.4,
                new Coordinate(0, 0), new Coordinate(50, 55), new Coordinate(100, 0));
        NetworkPlan south = candidate(VariantPolicy.INFRASTRUCTURE_LIGHT, 1.5,
                new Coordinate(0, 0), new Coordinate(50, -55), new Coordinate(100, 0));
        NetworkPlan east = candidate(VariantPolicy.FULL_COVERAGE, 1.6,
                new Coordinate(0, 0), new Coordinate(100, 55), new Coordinate(100, 0));

        List<NetworkPlan> selected = new MilpPortfolioSelector(new VariantDiversity())
                .select(Arrays.asList(sharedOfficial, sharedLight, sharedCoverage, north, south, east));

        assertEquals(3, selected.size());
        for (int i = 0; i < selected.size(); i++) {
            for (int j = i + 1; j < selected.size(); j++) {
                assertTrue(new VariantDiversity().materiallyDifferent(selected.get(i), selected.get(j)));
            }
        }
    }

    @Test
    void optimizesOnlyOfficialScoreAfterDiversityConstraintsAreSatisfied() {
        NetworkPlan cheapest = candidate(VariantPolicy.OFFICIAL_BEST, 1.00,
                new Coordinate(0, 0), new Coordinate(100, 0));
        NetworkPlan moreDifferentButWorse = candidate(VariantPolicy.OFFICIAL_BEST, 1.05,
                new Coordinate(0, 0), new Coordinate(50, 55), new Coordinate(100, 0));
        NetworkPlan light = candidate(VariantPolicy.INFRASTRUCTURE_LIGHT, 1.00,
                new Coordinate(0, 0), new Coordinate(100, 0));
        NetworkPlan coverage = candidate(VariantPolicy.FULL_COVERAGE, 1.00,
                new Coordinate(0, 0), new Coordinate(100, 0));
        light.roots.get(0).existingObjectId = "existing-2";
        coverage.roots.get(0).existingObjectId = "existing-3";

        List<NetworkPlan> selected = new MilpPortfolioSelector(new VariantDiversity())
                .select(Arrays.asList(cheapest, moreDifferentButWorse, light, coverage));

        assertTrue(selected.contains(cheapest));
        assertTrue(!selected.contains(moreDifferentButWorse));
    }

    private NetworkPlan candidate(VariantPolicy policy, double score, Coordinate... coordinates) {
        NetworkPlan plan = new VariantDiversityTestSupport(geometryFactory).plan(coordinates);
        plan.policy = policy;
        plan.score = score;
        return plan;
    }
}

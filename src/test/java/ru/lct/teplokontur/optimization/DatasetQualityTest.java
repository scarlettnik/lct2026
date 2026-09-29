package ru.lct.teplokontur.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;
import ru.lct.teplokontur.config.CompetitionProperties;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.*;
import ru.lct.teplokontur.ingest.*;
import ru.lct.teplokontur.persistence.*;
import ru.lct.teplokontur.service.SnapshotLoader;
import ru.lct.teplokontur.validation.EngineeringValidator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DatasetQualityTest {
    static InputSnapshot snapshot() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CrsTransformer crs = new CrsTransformer();
        List<FeatureEntity> rows = new ArrayList<>();
        for (JsonNode feature : mapper.readTree(Path.of("task/Датасет скорректированный.geojson").toFile()).path("features")) {
            JsonNode p = feature.path("properties");
            FeatureEntity row = new FeatureEntity();
            row.rowId = (long) rows.size() + 1;
            row.featureId = p.path("id").asText();
            row.objectType = p.path("object_type").asText();
            row.oksId = p.hasNonNull("oks_id") ? p.path("oks_id").asText() : row.featureId;
            row.upstreamObjectId = p.path("upstream_object_id").asText(null);
            row.diameter = p.path("diameter").asInt(200);
            row.flowTph = p.path("flow_tph").asDouble();
            row.restrictionType = p.path("restriction_type").asText(null);
            row.propertiesJson = p.toString();
            row.geometryJson = feature.path("geometry").toString();
            Geometry geometry = crs.toUtm(GeoJsonGeometry.parse(feature.path("geometry")));
            row.geometryWkb = new WKBWriter().write(geometry);
            rows.add(row);
        }
        FeatureRepository repo = mock(FeatureRepository.class);
        when(repo.findByScenarioIdAndObjectType(anyLong(), anyString())).thenAnswer(call -> rows.stream()
                .filter(row -> row.objectType.equals(call.getArgument(1))).collect(Collectors.toList()));
        when(repo.findByScenarioIdOrderByRowIdAsc(anyLong())).thenReturn(rows);
        when(repo.bbox(anyLong(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(rows);
        return new SnapshotLoader(repo, new GeometryStore(), new CompetitionProperties()).load(1);
    }

    @Test void restoresMissingUpstreamReferencesFromInputGeometry() throws Exception {
        InputSnapshot snapshot=snapshot();
        Map<String,String> upstream=new HashMap<>();
        for(InputSnapshot.ExistingEdge edge:snapshot.network){assertNotNull(edge.upstream);upstream.put(edge.id,edge.upstream);}
        for(InputSnapshot.ExistingChamber chamber:snapshot.chambers){assertNotNull(chamber.upstream);upstream.put(chamber.id,chamber.upstream);}
        String source=snapshot.features.stream().filter(f->"source".equals(f.objectType)).findFirst().orElseThrow().id;
        for(String id:upstream.keySet()) {
            Set<String> seen=new HashSet<>();String current=id;
            while(!source.equals(current)){assertTrue(seen.add(current),"Upstream cycle: "+id);current=upstream.get(current);assertNotNull(current,"Source unreachable: "+id);}
        }
    }

    @Test void retainsFullCoverageAndBaselineQuality() throws Exception {
        InputSnapshot snapshot = snapshot();
        java.nio.file.Files.createDirectories(Path.of("target/optimized-results"));
        double twoD=Double.POSITIVE_INFINITY;
        List<org.junit.jupiter.api.function.Executable> qualityTargets=new ArrayList<>();
        for(RunMode mode:new RunMode[]{RunMode.TWO_D,RunMode.DEPTH}) {
            if(!System.getProperty("quality.mode","both").equals("both")&&!System.getProperty("quality.mode").equals(mode.name()))continue;
            long start=System.nanoTime();
            List<NetworkPlan> plans=new UniversalOptimizer(new CompetitionProperties()).optimize(snapshot,mode);
            String name=mode==RunMode.TWO_D?"2d":"3d";
            Path output=Path.of("target/optimized-results/result-"+name+".geojson");
            ObjectMapper mapper=new ObjectMapper();
            try(java.io.OutputStream stream=java.nio.file.Files.newOutputStream(output)) {
                new ru.lct.teplokontur.export.GeoJsonResultWriter(mapper,new CrsTransformer()).write(stream,snapshot,plans);
            }
            assertTrue(new ru.lct.teplokontur.validation.OutputGeoJsonValidator(mapper).validate(output).isEmpty());
            assertShortestBuildingEntries(snapshot,output);
            System.out.printf(Locale.ROOT,"QUALITY mode=%s scores=%s seconds=%.3f%n",mode,
                    plans.stream().map(p->p.score).collect(Collectors.toList()),(System.nanoTime()-start)/1e9);
            qualityTargets.add(()->assertEquals(3,plans.size(),"Three materially different plans required"));
            for(int i=0;i<plans.size();i++) {
                NetworkPlan plan=plans.get(i);
                assertTrue(plan.validation.passed,plan.validation.errors.toString());
                qualityTargets.add(()->assertTrue(plan.unconnected.isEmpty(),mode+" unconnected: "+plan.unconnected));
                for(int j=0;j<i;j++)assertTrue(new VariantDiversity().materiallyDifferent(plan,plans.get(j)));
            }
            qualityTargets.add(()->assertTrue(plans.get(0).score<13.4,"S must be below 13.4: "+plans.get(0).score));
            if(mode==RunMode.TWO_D)twoD=plans.get(0).score;
            else {final double twoDScore=twoD;qualityTargets.add(()->assertTrue(plans.get(0).score<twoDScore,"3D best must improve on 2D best"));}
        }
        assertAll("Optimization targets (after checking both exported geometries)",qualityTargets);
    }

    /** Audit exported coordinates directly, independently of the route validator. */
    static void assertShortestBuildingEntries(InputSnapshot snapshot,Path output) throws Exception {
        Map<String,List<Geometry>> networks=new LinkedHashMap<>();
        CrsTransformer crs=new CrsTransformer();
        for(JsonNode feature:new ObjectMapper().readTree(output.toFile()).path("features")) {
            JsonNode properties=feature.path("properties");
            if(!"heat_network".equals(properties.path("object_type").asText()))continue;
            networks.computeIfAbsent(properties.path("variant_id").asText(),key->new ArrayList<>())
                    .add(crs.toUtm(GeoJsonGeometry.parse(feature.path("geometry"))));
        }
        int checked=0;
        for(Map.Entry<String,List<Geometry>> variant:networks.entrySet()) {
            for(InputSnapshot.Restriction building:snapshot.restrictions) {
                if(!"oks_existing".equals(building.type))continue;
                org.locationtech.jts.operation.linemerge.LineMerger merger=new org.locationtech.jts.operation.linemerge.LineMerger();
                for(Geometry route:variant.getValue())if(route.getEnvelopeInternal().intersects(building.geometry.getEnvelopeInternal()))
                    merger.add(route.intersection(building.geometry));
                for(Object value:merger.getMergedLineStrings()) {
                    org.locationtech.jts.geom.LineString lead=(org.locationtech.jts.geom.LineString)value;
                    if(lead.getLength()<1e-5)continue;
                    InputSnapshot.Terminal terminal=snapshot.terminals.stream().filter(t->building.geometry.covers(t.point)
                            &&(t.point.getCoordinate().distance(lead.getCoordinateN(0))<1e-4
                            ||t.point.getCoordinate().distance(lead.getCoordinateN(lead.getNumPoints()-1))<1e-4)).findFirst().orElse(null);
                    assertNotNull(terminal,"Transit under building "+building.id+" in "+variant.getKey());
                    double shortest=Double.POSITIVE_INFINITY;
                    for(int polygon=0;polygon<building.geometry.getNumGeometries();polygon++)
                        shortest=Math.min(shortest,((org.locationtech.jts.geom.Polygon)building.geometry.getGeometryN(polygon))
                                .getExteriorRing().distance(terminal.point));
                    assertEquals(shortest,lead.getLength(),.002,"Wrong facade for "+terminal.pointId+" / "+building.id+" in "+variant.getKey());
                    checked++;
                }
            }
        }
        System.out.println("BUILDING_ENTRY_AUDIT file="+output+" checked="+checked+" violations=0");
    }
}

package ru.lct.teplokontur.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.NetworkPlan;
import ru.lct.teplokontur.ingest.CrsTransformer;

class GeoJsonResultWriterTest {
    @org.junit.jupiter.api.Test
    void exportsDepthAtEveryVertexAndKeepsTwoDPlanar() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        org.locationtech.jts.geom.GeometryFactory gf=new org.locationtech.jts.geom.GeometryFactory();
        InputSnapshot snapshot=new InputSnapshot(2);
        InputSnapshot.ExistingEdge existing=new InputSnapshot.ExistingEdge();existing.id="network";existing.dn=300;
        existing.geometry=gf.createLineString(new org.locationtech.jts.geom.Coordinate[]{new org.locationtech.jts.geom.Coordinate(400000,6100000),new org.locationtech.jts.geom.Coordinate(400010,6100000)});
        snapshot.network.add(existing);
        InputSnapshot.Terminal terminal=new InputSnapshot.Terminal();terminal.oksId="consumer";terminal.pointId="entry";terminal.flow=20;
        terminal.point=gf.createPoint(new org.locationtech.jts.geom.Coordinate(400010,6100100));snapshot.terminals.add(terminal);
        InputSnapshot.Restriction cable=new InputSnapshot.Restriction();cable.id="cable";cable.type="power_cable";
        cable.geometry=gf.createLineString(new org.locationtech.jts.geom.Coordinate[]{new org.locationtech.jts.geom.Coordinate(400000,6100050),new org.locationtech.jts.geom.Coordinate(400020,6100050)});snapshot.restrictions.add(cable);
        for(ru.lct.teplokontur.domain.RunMode mode:ru.lct.teplokontur.domain.RunMode.values()) {
            NetworkPlan plan=new NetworkPlan();plan.mode=mode;plan.variantId="variant";
            ru.lct.teplokontur.optimization.PlanFactory factory=new ru.lct.teplokontur.optimization.PlanFactory();
            ru.lct.teplokontur.domain.PlanNode root=factory.root(plan,new ru.lct.teplokontur.domain.TieCandidate(existing.id,"heat_network",gf.createPoint(existing.geometry.getCoordinateN(1)),300,10));
            ru.lct.teplokontur.domain.PlanNode leaf=factory.terminal(plan,terminal);
            factory.edge(plan,root,leaf,(org.locationtech.jts.geom.LineString)new org.locationtech.jts.io.WKBReader().read(new org.locationtech.jts.io.WKBWriter().write(gf.createLineString(new org.locationtech.jts.geom.Coordinate[]{root.point.getCoordinate(),leaf.point.getCoordinate()}))));
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            new GeoJsonResultWriter(mapper,new CrsTransformer()).write(output,snapshot,Collections.singletonList(plan));
            double cost=0;
            for(JsonNode feature:mapper.readTree(output.toByteArray()).path("features")) {
                JsonNode properties=feature.path("properties"),geometry=feature.path("geometry");
                cost+=properties.path("cost").asDouble();
                if(geometry.isNull()){assertEquals(plan.calculatedCost,cost,1e-6);continue;}
                JsonNode coordinates=geometry.path("coordinates");
                int dimensions=mode==ru.lct.teplokontur.domain.RunMode.DEPTH?3:2;
                if(geometry.path("type").asText().equals("Point"))assertEquals(dimensions,coordinates.size());
                else {
                    for(JsonNode coordinate:coordinates)assertEquals(dimensions,coordinate.size());
                    if(dimensions==3){assertEquals(-properties.path("depth_start").asDouble(),coordinates.get(0).get(2).asDouble(),1e-8);assertEquals(-properties.path("depth_end").asDouble(),coordinates.get(coordinates.size()-1).get(2).asDouble(),1e-8);}
                }
            }
        }
    }
}

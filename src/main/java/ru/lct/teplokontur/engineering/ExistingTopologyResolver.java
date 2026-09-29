package ru.lct.teplokontur.engineering;

import java.util.*;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.teplokontur.domain.InputSnapshot;

/** Reconstructs missing upstream references from connected pipe endpoints only. */
public final class ExistingTopologyResolver {
    private static final double SNAP_METRES=2;
    private static final class Vertex {
        final Coordinate point;
        final List<Link> links=new ArrayList<>();
        double distance=Double.POSITIVE_INFINITY;
        String parentEdge;
        InputSnapshot.ExistingChamber chamber;
        Vertex(Coordinate point){this.point=point;}
    }
    private static final class Link {
        final Vertex other;final InputSnapshot.ExistingEdge edge;
        Link(Vertex other,InputSnapshot.ExistingEdge edge){this.other=other;this.edge=edge;}
    }

    public void resolve(InputSnapshot snapshot,String sourceId,Set<String> missingDiameters) {
        if(snapshot.source==null)return;
        List<Vertex> vertices=new ArrayList<>();
        Vertex source=vertex(vertices,snapshot.source.getCoordinate());
        Map<InputSnapshot.ExistingEdge,Vertex[]> endpoints=new LinkedHashMap<>();
        for(InputSnapshot.ExistingEdge edge:snapshot.network) {
            Vertex a=vertex(vertices,edge.geometry.getCoordinateN(0));
            Vertex b=vertex(vertices,edge.geometry.getCoordinateN(edge.geometry.getNumPoints()-1));
            a.links.add(new Link(b,edge));b.links.add(new Link(a,edge));endpoints.put(edge,new Vertex[]{a,b});
        }
        for(InputSnapshot.ExistingChamber chamber:snapshot.chambers) {
            Vertex node=vertex(vertices,chamber.point.getCoordinate());node.chamber=chamber;
            if(missingDiameters.contains(chamber.id))chamber.dn=node.links.stream().mapToInt(link->link.edge.dn).max().orElse(chamber.dn);
        }
        source.distance=0;
        Set<Vertex> pending=new LinkedHashSet<>(vertices);
        while(!pending.isEmpty()) {
            Vertex current=pending.stream().min(Comparator.comparingDouble(v->v.distance)).orElseThrow();
            pending.remove(current);if(!Double.isFinite(current.distance))break;
            for(Link link:current.links) {
                double distance=current.distance+link.edge.geometry.getLength();
                if(pending.contains(link.other)&&distance<link.other.distance-1e-8){link.other.distance=distance;link.other.parentEdge=link.edge.id;}
            }
        }
        for(Map.Entry<InputSnapshot.ExistingEdge,Vertex[]> entry:endpoints.entrySet()) {
            InputSnapshot.ExistingEdge edge=entry.getKey();if(edge.upstream!=null)continue;
            Vertex[] pair=entry.getValue();Vertex up=pair[0].distance<=pair[1].distance?pair[0]:pair[1];
            if(!Double.isFinite(up.distance))throw new IllegalArgumentException("Existing pipe disconnected from source: "+edge.id);
            edge.upstream=up==source?sourceId:up.chamber!=null?up.chamber.id:up.parentEdge;
        }
        for(Vertex node:vertices)if(node.chamber!=null&&node.chamber.upstream==null) {
            if(!Double.isFinite(node.distance))throw new IllegalArgumentException("Existing chamber disconnected from source: "+node.chamber.id);
            node.chamber.upstream=node==source?sourceId:node.parentEdge;
        }
    }

    private Vertex vertex(List<Vertex> vertices,Coordinate point) {
        for(Vertex vertex:vertices)if(vertex.point.distance(point)<SNAP_METRES)return vertex;
        Vertex result=new Vertex(point);vertices.add(result);return result;
    }
}

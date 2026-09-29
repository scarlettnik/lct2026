package ru.lct.teplokontur.domain;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Counts every existing linear section that terminates at a chamber. */
public final class ExistingChamberRules {
    private ExistingChamberRules() { }

    public static int existingConnections(InputSnapshot snapshot,InputSnapshot.ExistingChamber chamber) {
        int count=0;
        for(InputSnapshot.ExistingEdge edge:snapshot.network) {
            LengthIndexedLine indexed=new LengthIndexedLine(edge.geometry);
            Coordinate point=chamber.point.getCoordinate();double chainage=indexed.project(point);
            if(indexed.extractPoint(chainage).distance(point)>=2)continue;
            count+=sectionsAt(edge.geometry,chainage);
        }
        return count;
    }

    public static int pipeTieConnections(InputSnapshot snapshot,PlanNode root) {
        int count=0;
        for(InputSnapshot.ExistingEdge edge:snapshot.network) {
            LengthIndexedLine indexed=new LengthIndexedLine(edge.geometry);
            double chainage=indexed.project(root.point.getCoordinate());
            if(indexed.extractPoint(chainage).distance(root.point.getCoordinate())>=2)continue;
            count+=sectionsAt(edge.geometry,chainage);
        }
        return count;
    }

    public static int plannedConnections(NetworkPlan plan,String chamberId) {
        int count=0;
        for(PlanNode root:plan.roots)if("heat_chamber".equals(root.existingObjectType)
                &&chamberId.equals(root.existingObjectId))count+=root.children.size();
        return count;
    }

    private static int sectionsAt(LineString geometry,double chainage) {
        double length=geometry.getLength();boolean atStart=chainage<2,atEnd=length-chainage<2;
        if(atStart&&atEnd)return 2;if(atStart||atEnd)return 1;return 2;
    }
}

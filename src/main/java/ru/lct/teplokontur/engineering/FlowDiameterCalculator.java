package ru.lct.teplokontur.engineering;

import ru.lct.teplokontur.domain.*;
import java.util.*;

public class FlowDiameterCalculator {
    public void apply(NetworkPlan plan,Map<String,Double> terminalFlows) {
        Map<PlanEdge,Integer> sizes=PipeSizing.requiredDiameters(plan,terminalFlows);
        for(Map.Entry<PlanEdge,Integer> entry:sizes.entrySet())entry.getKey().dn=entry.getValue();
    }
}

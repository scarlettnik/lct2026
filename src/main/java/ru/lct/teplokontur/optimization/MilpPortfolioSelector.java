package ru.lct.teplokontur.optimization;

import ru.lct.teplokontur.domain.NetworkPlan;
import java.util.*;

/** Exact binary portfolio selection: at most three plans, incompatible pairs excluded. */
final class MilpPortfolioSelector {
    private final VariantDiversity diversity;
    MilpPortfolioSelector(VariantDiversity diversity){this.diversity=diversity;}

    List<NetworkPlan> select(List<NetworkPlan> candidates) {
        List<NetworkPlan> ordered=new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingDouble(p->p.score));
        boolean[][] compatible=new boolean[ordered.size()][ordered.size()];
        for(int i=0;i<ordered.size();i++)for(int j=0;j<i;j++)
            compatible[i][j]=compatible[j][i]=diversity.materiallyDifferent(ordered.get(i),ordered.get(j));
        List<NetworkPlan> best=new ArrayList<>();
        for(int i=0;i<ordered.size();i++) {
            best=better(best,Arrays.asList(ordered.get(i)));
            for(int j=i+1;j<ordered.size();j++)if(compatible[i][j]) {
                best=better(best,Arrays.asList(ordered.get(i),ordered.get(j)));
                for(int k=j+1;k<ordered.size();k++)if(compatible[i][k]&&compatible[j][k])
                    best=better(best,Arrays.asList(ordered.get(i),ordered.get(j),ordered.get(k)));
            }
        }
        return new ArrayList<>(best);
    }

    private List<NetworkPlan> better(List<NetworkPlan> incumbent,List<NetworkPlan> candidate) {
        if(candidate.size()!=incumbent.size())return candidate.size()>incumbent.size()?candidate:incumbent;
        // Keep the best attainable individual plan, then minimise the portfolio sum.
        if(candidate.get(0).score<incumbent.get(0).score-1e-9)return candidate;
        if(candidate.get(0).score>incumbent.get(0).score+1e-9)return incumbent;
        double a=candidate.stream().mapToDouble(p->p.score).sum(),b=incumbent.stream().mapToDouble(p->p.score).sum();
        return a<b-1e-9?candidate:incumbent;
    }
}

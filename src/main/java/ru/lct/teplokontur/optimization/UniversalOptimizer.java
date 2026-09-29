package ru.lct.teplokontur.optimization;

import org.springframework.stereotype.Component;
import ru.lct.teplokontur.config.CompetitionProperties;
import ru.lct.teplokontur.domain.*;
import ru.lct.teplokontur.engineering.PlanEvaluator;
import ru.lct.teplokontur.validation.EngineeringValidator;
import java.util.*;

@Component
public class UniversalOptimizer {
    private final CompetitionProperties props;
    public UniversalOptimizer(CompetitionProperties props){this.props=props;}

    public List<NetworkPlan> optimize(InputSnapshot snapshot,RunMode mode) {
        SharedNetworkBuilder builder=new SharedNetworkBuilder(props);
        PlanEvaluator evaluator=new PlanEvaluator();
        EngineeringValidator validator=new EngineeringValidator();
        JointPlanRefiner jointRefiner=new JointPlanRefiner();
        JunctionRefiner junctionRefiner=new JunctionRefiner();
        PlanLocalSearch localSearch=new PlanLocalSearch();
        List<NetworkPlan> candidates=new ArrayList<>();
        int attempts=Math.max(3,Math.min(6,props.getOptimizer().getKPaths()+1));
        for(int seed=0;seed<attempts;seed++) {
            int salt=seed==0?0:seed+(int)Math.floorMod(props.getOptimizer().getRandomSeed(),10000);
            NetworkPlan plan=builder.build(snapshot,mode,salt);
            junctionRefiner.improve(snapshot,plan);
            plan=jointRefiner.improve(snapshot,plan);
            archive(snapshot,plan,seed,candidates,evaluator,validator);
            for(int round=0;round<(mode==RunMode.DEPTH?3:2);round++) {
                double before=plan.score;
                plan=builder.reconnectLeaves(snapshot,plan);
                localSearch.improve(snapshot,plan,evaluator,validator);
                plan=jointRefiner.improve(snapshot,plan);
                archive(snapshot,plan,seed,candidates,evaluator,validator);
                if(plan.score>=before-1e-7)break;
            }
            plan.policy=VariantPolicy.values()[seed%VariantPolicy.values().length];
            evaluator.evaluate(snapshot,plan);plan.validation=validator.validate(snapshot,plan);
            if(plan.validation.passed)candidates.add(plan);
            System.out.printf(Locale.ROOT,"CANDIDATE mode=%s seed=%d score=%.9f valid=%s unconnected=%d%n",
                    mode,seed,plan.score,plan.validation.passed,plan.unconnected.size());
            if(seed==attempts-1&&attempts<8&&new MilpPortfolioSelector(new VariantDiversity()).select(bestCoverage(candidates)).size()<3)attempts++;
        }
        if(candidates.isEmpty())throw new IllegalStateException("No engineering-valid plans");
        List<NetworkPlan> selected=new MilpPortfolioSelector(new VariantDiversity()).select(bestCoverage(candidates));
        selected.sort(Comparator.comparingDouble(p->p.score));
        for(int i=0;i<selected.size();i++)selected.get(i).variantId=(mode==RunMode.TWO_D?"2d_":"3d_")+(i+1);
        return selected;
    }
    private List<NetworkPlan> bestCoverage(List<NetworkPlan> plans) {
        int missing=plans.stream().mapToInt(p->p.unconnected.size()).min().orElse(0);
        List<NetworkPlan> result=new ArrayList<>();
        for(NetworkPlan plan:plans)if(plan.unconnected.size()==missing)result.add(plan);
        return result;
    }

    private void archive(InputSnapshot snapshot,NetworkPlan plan,int seed,List<NetworkPlan> candidates,
                         PlanEvaluator evaluator,EngineeringValidator validator) {
        NetworkPlan copy=SharedNetworkBuilder.copy(plan);
        copy.policy=VariantPolicy.values()[seed%VariantPolicy.values().length];
        evaluator.evaluate(snapshot,copy);copy.validation=validator.validate(snapshot,copy);
        if(copy.validation.passed)candidates.add(copy);
    }
}

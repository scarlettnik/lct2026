package ru.lct.teplokontur.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
@Configuration
@ConfigurationProperties(prefix="competition")
public class CompetitionProperties {
  private final Routing routing=new Routing(); private final Optimizer optimizer=new Optimizer();
  public Routing getRouting(){return routing;} public Optimizer getOptimizer(){return optimizer;}
  public static class Routing {private double gridStepM=3,corridorPaddingM=120,workingSetPaddingM=5000;private int maxExpandedCells=900000;public double getGridStepM(){return gridStepM;}public void setGridStepM(double v){gridStepM=v;}public double getCorridorPaddingM(){return corridorPaddingM;}public void setCorridorPaddingM(double v){corridorPaddingM=v;}public int getMaxExpandedCells(){return maxExpandedCells;}public void setMaxExpandedCells(int v){maxExpandedCells=v;}public double getWorkingSetPaddingM(){return workingSetPaddingM;}public void setWorkingSetPaddingM(double v){workingSetPaddingM=v;}}
  public static class Optimizer {private int beamWidth=24,tieCandidates=8,kPaths=4;private long randomSeed=20260922;public int getBeamWidth(){return beamWidth;}public void setBeamWidth(int v){beamWidth=v;}public int getTieCandidates(){return tieCandidates;}public void setTieCandidates(int v){tieCandidates=v;}public int getKPaths(){return kPaths;}public void setKPaths(int v){kPaths=v;}public long getRandomSeed(){return randomSeed;}public void setRandomSeed(long v){randomSeed=v;}}
}

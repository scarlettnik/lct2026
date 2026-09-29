package ru.lct.teplokontur.optimization;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Exact top-k solver for the binary chamber-position MILP. Its variables are
 * {@code x[node,candidate]} and {@code y[parent,left,child,right]}:
 * {@code x[node,candidate]} selects a chamber position and
 * {@code y[parent,left,child,right]} linearises an edge cost.
 *
 * <p>The input graph is a rooted tree.  Eliminating the equality-constrained
 * {@code y} variables leaves a tree factorisation, so a specialised exact
 * solver is materially faster than repeatedly handing the same MILP to a
 * generic branch-and-bound engine.  It produces the same first {@code limit}
 * distinct assignments as repeated MILP solves with no-good cuts; infinities
 * are equivalent to an upper bound of zero on the corresponding y variable.</p>
 */
public final class JointChamberMilp {
    private static final double EPS = 1e-9;

    public List<Assignment> solve(int root, Map<Integer, Integer> candidateCounts,
                           Map<Edge, double[][]> edgeCosts, int limit) {
        if (limit <= 0 || !candidateCounts.containsKey(root)) {
            return Collections.emptyList();
        }
        Map<Integer, List<Edge>> children = children(candidateCounts, edgeCosts);
        verifyRootedTree(root, candidateCounts, children);
        Map<Integer, List<List<Solution>>> memo = new HashMap<>();
        List<List<Solution>> rootByCandidate = solveNode(root, candidateCounts, children, limit, memo);
        List<Solution> all = new ArrayList<>();
        for (List<Solution> solutions : rootByCandidate) {
            all.addAll(solutions);
        }
        all.sort(SOLUTION_ORDER);
        List<Assignment> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Solution solution : all) {
            String signature = solution.signature();
            if (seen.add(signature)) {
                result.add(new Assignment(solution.cost, solution.selected));
            }
            if (result.size() == limit) {
                break;
            }
        }
        return result;
    }

    private List<List<Solution>> solveNode(int node, Map<Integer, Integer> counts,
                                            Map<Integer, List<Edge>> children, int limit,
                                            Map<Integer, List<List<Solution>>> memo) {
        List<List<Solution>> cached = memo.get(node);
        if (cached != null) {
            return cached;
        }
        List<List<Solution>> result = new ArrayList<>();
        for (int candidate = 0; candidate < counts.get(node); candidate++) {
            List<List<Solution>> childAlternatives = new ArrayList<>();
            boolean feasible = true;
            for (Edge edge : children.get(node)) {
                List<List<Solution>> childByCandidate = solveNode(edge.child, counts, children, limit, memo);
                List<Solution> alternatives = new ArrayList<>();
                for (int childCandidate = 0; childCandidate < counts.get(edge.child); childCandidate++) {
                    double edgeCost = edge.costs[candidate][childCandidate];
                    if (!Double.isFinite(edgeCost)) {
                        continue;
                    }
                    for (Solution child : childByCandidate.get(childCandidate)) {
                        alternatives.add(child.plus(edgeCost));
                    }
                }
                alternatives.sort(SOLUTION_ORDER);
                if (alternatives.size() > limit) {
                    alternatives = new ArrayList<>(alternatives.subList(0, limit));
                }
                if (alternatives.isEmpty()) {
                    feasible = false;
                    break;
                }
                childAlternatives.add(alternatives);
            }
            result.add(feasible ? merge(node, candidate, childAlternatives, limit) : Collections.emptyList());
        }
        memo.put(node, result);
        return result;
    }

    /** k smallest sums of one sorted solution list for each child branch. */
    private List<Solution> merge(int node, int candidate, List<List<Solution>> branches, int limit) {
        if (branches.isEmpty()) {
            return Collections.singletonList(Solution.with(node, candidate));
        }
        PriorityQueue<MergeState> queue = new PriorityQueue<>(Comparator.comparingDouble(state -> state.cost));
        Set<String> seen = new HashSet<>();
        int[] initial = new int[branches.size()];
        offer(queue, seen, initial, branches);
        List<Solution> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < limit) {
            MergeState state = queue.poll();
            Map<Integer, Integer> selected = new LinkedHashMap<>();
            selected.put(node, candidate);
            for (int branch = 0; branch < branches.size(); branch++) {
                selected.putAll(branches.get(branch).get(state.indices[branch]).selected);
            }
            result.add(new Solution(state.cost, selected));
            for (int branch = 0; branch < state.indices.length; branch++) {
                int[] neighbour = state.indices.clone();
                neighbour[branch]++;
                if (neighbour[branch] < branches.get(branch).size()) {
                    offer(queue, seen, neighbour, branches);
                }
            }
        }
        result.sort(SOLUTION_ORDER);
        return result;
    }

    private void offer(PriorityQueue<MergeState> queue, Set<String> seen, int[] indices,
                       List<List<Solution>> branches) {
        String key = indicesKey(indices);
        if (!seen.add(key)) {
            return;
        }
        double cost = 0.0;
        for (int branch = 0; branch < branches.size(); branch++) {
            cost += branches.get(branch).get(indices[branch]).cost;
        }
        queue.add(new MergeState(indices, cost));
    }

    private Map<Integer, List<Edge>> children(Map<Integer, Integer> counts, Map<Edge, double[][]> costs) {
        Map<Integer, List<Edge>> result = new HashMap<>();
        for (Integer node : counts.keySet()) {
            if (counts.get(node) == null || counts.get(node) <= 0) {
                throw new IllegalArgumentException("every node needs at least one candidate");
            }
            result.put(node, new ArrayList<>());
        }
        for (Map.Entry<Edge, double[][]> entry : costs.entrySet()) {
            Edge original = entry.getKey();
            if (!result.containsKey(original.parent) || !result.containsKey(original.child)
                    || original.parent == original.child) {
                throw new IllegalArgumentException("edge endpoints must be distinct chamber nodes");
            }
            validateMatrix(entry.getValue(), counts.get(original.parent), counts.get(original.child));
            result.get(original.parent).add(new Edge(original.parent, original.child, entry.getValue()));
        }
        for (List<Edge> edges : result.values()) {
            edges.sort(Comparator.comparingInt(edge -> edge.child));
        }
        return result;
    }

    private void verifyRootedTree(int root, Map<Integer, Integer> counts, Map<Integer, List<Edge>> children) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            int node = pending.removeFirst();
            if (!visited.add(node)) {
                throw new IllegalArgumentException("joint MILP costs must form a rooted tree");
            }
            for (Edge edge : children.get(node)) {
                pending.addLast(edge.child);
            }
        }
        if (visited.size() != counts.size()) {
            throw new IllegalArgumentException("every chamber node must be reachable from the supplied root");
        }
    }

    private void validateMatrix(double[][] matrix, int rows, int columns) {
        if (matrix == null || matrix.length != rows) {
            throw new IllegalArgumentException("cost matrix row count does not match parent candidates");
        }
        for (double[] row : matrix) {
            if (row == null || row.length != columns) {
                throw new IllegalArgumentException("cost matrix column count does not match child candidates");
            }
        }
    }

    private static String indicesKey(int[] values) {
        StringBuilder result = new StringBuilder();
        for (int value : values) {
            result.append(value).append(',');
        }
        return result.toString();
    }

    private static String signature(Map<Integer, Integer> selected) {
        StringBuilder result = new StringBuilder();
        selected.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.append(entry.getKey()).append(':').append(entry.getValue()).append(';'));
        return result.toString();
    }

    public static final class Edge {
        final int parent;
        final int child;
        final double[][] costs;

        public Edge(int parent, int child) {
            this(parent, child, null);
        }

        Edge(int parent, int child, double[][] costs) {
            this.parent = parent;
            this.child = child;
            this.costs = costs;
        }

        @Override public boolean equals(Object object) {
            if (!(object instanceof Edge)) return false;
            Edge other = (Edge) object;
            return parent == other.parent && child == other.child;
        }

        @Override public int hashCode() { return Objects.hash(parent, child); }
    }

    public static final class Assignment {
        private final double cost;
        private final Map<Integer, Integer> selected;

        Assignment(double cost, Map<Integer, Integer> selected) {
            this.cost = cost;
            this.selected = Collections.unmodifiableMap(new LinkedHashMap<>(selected));
        }

        public double cost() { return cost; }
        public Map<Integer, Integer> selected() { return selected; }
    }

    private static final Comparator<Solution> SOLUTION_ORDER = (left, right) -> {
        int byCost = Double.compare(left.cost, right.cost);
        return byCost != 0 ? byCost : left.signature().compareTo(right.signature());
    };

    private static final class Solution {
        private final double cost;
        private final Map<Integer, Integer> selected;
        private String cachedSignature;

        private Solution(double cost, Map<Integer, Integer> selected) {
            this.cost = cost;
            this.selected = selected;
        }

        static Solution with(int node, int candidate) {
            Map<Integer, Integer> selected = new LinkedHashMap<>();
            selected.put(node, candidate);
            return new Solution(0.0, selected);
        }

        Solution plus(double value) {
            Solution result=new Solution(cost + value, selected);
            result.cachedSignature=cachedSignature;
            return result;
        }

        private String signature() {
            if(cachedSignature==null)cachedSignature=JointChamberMilp.signature(selected);
            return cachedSignature;
        }
    }

    private static final class MergeState {
        private final int[] indices;
        private final double cost;
        private MergeState(int[] indices, double cost) { this.indices = indices; this.cost = cost; }
    }
}

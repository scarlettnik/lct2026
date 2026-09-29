package ru.lct.teplokontur.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.Point;
import ru.lct.teplokontur.domain.DnSpec;
import ru.lct.teplokontur.domain.InputSnapshot;
import ru.lct.teplokontur.domain.RestrictionRule;
import ru.lct.teplokontur.domain.RestrictionRules;
import ru.lct.teplokontur.domain.RunMode;
import ru.lct.teplokontur.domain.RuleBook;

/**
 * Sparse exact-geometry visibility-graph router.
 *
 * <p>Unlike {@link AdaptiveGridRouter}, this class never expands a raster
 * envelope.  It samples the buffered corners of exact restrictions, creates a
 * sparse visibility graph, then searches states {@code (node, previousNode)}
 * so the 90-degree/no-reversal rule is enforced during the search.</p>
 */
public final class SparseVisibilityRouter {
    private static final int MAX_CANDIDATES = 1600;
    /** Uses an angular visibility sweep: two usable edges in each of 16 directions. */
    private static final int VISIBILITY_SECTORS = 16;
    private static final int NEIGHBORS_PER_SECTOR = 2;
    /** Fast construction bounds the nearest-node and sector-probe search. */
    private static final int FAST_NEAREST_CANDIDATES = 64;
    private static final int ATTEMPTS_PER_SECTOR = 6;
    /** A meaningful bend carries an eight-metre equivalent routing cost. */
    private static final double BEND_PENALTY_METERS = 8.0;
    private static final double[] CORRIDOR_PADDING_METERS = {120.0, 360.0, 720.0};
    private final GeometryFactory geometryFactory = new GeometryFactory();
    /**
     * The visibility graph is constructed once and reused for all route
     * attempts in a portfolio. Keep the same lifetime here: tie-candidate
     * retries must not rebuild immutable restriction buffers and indexes.
     */
    private InputSnapshot cachedSnapshot;
    private RouteConstraintEngine cachedConstraints;
    private final Map<Integer, List<RestrictionBoundary>> boundariesByDiameter = new HashMap<>();
    /**
     * The visibility graph is a scenario object, not a per-request helper.
     * These templates are its Java equivalent: immutable obstacle vertices and
     * their exact, DN-aware visibility edges.  Route searches only splice in
     * the two requested endpoints and reject edges blocked by the current
     * partially-built network.
     */
    private final Map<GraphKey, GraphTemplate> staticGraphs = new HashMap<>();
    private final Map<String,Optional<LineString>> routesWithoutNewLines=new HashMap<>();
    private final Map<String,RouteAssessment> entryAssessments=new HashMap<>();
    private final Map<String,List<Coordinate>> approachesByGoal=boundedGeometryCache();
    private final Map<String,List<Coordinate>> escapesByGoal=boundedGeometryCache();
    private int staticGraphBuilds;

    private static Map<String,List<Coordinate>> boundedGeometryCache() {
        return new java.util.LinkedHashMap<String,List<Coordinate>>(64,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,List<Coordinate>> entry){return size()>4096;}
        };
    }

    public Optional<LineString> route(InputSnapshot snapshot, Point start, Point goal, int diameter,
                                      RunMode mode, List<LineString> occupied, long salt) {
        constraintsFor(snapshot);
        String cacheKey=start.getX()+":"+start.getY()+":"+goal.getX()+":"+goal.getY()+":"+diameter+":"+mode;
        Optional<LineString> baseline=routesWithoutNewLines.computeIfAbsent(cacheKey,
                key->search(snapshot,start,goal,diameter,mode,Collections.emptyList()));
        // Additional new lines can only remove routes. Reuse failed searches
        // and unobstructed paths while growing and refining candidate networks.
        if(baseline.isEmpty()||occupied==null||occupied.isEmpty()
                ||!blockedByNewLines(baseline.get(),occupied,start.getCoordinate()))
            return baseline.map(line->(LineString)line.copy());
        return search(snapshot,start,goal,diameter,mode,occupied);
    }

    private Optional<LineString> search(InputSnapshot snapshot,Point start,Point goal,int diameter,
                                        RunMode mode,List<LineString> occupied) {
        Optional<LineString> route=search(snapshot,start,goal,diameter,mode,occupied,Math.max(200,diameter));
        // Reuse the common trunk graph first; narrow pipes also get their exact clearances.
        return route.isPresent()||diameter>=200?route:search(snapshot,start,goal,diameter,mode,occupied,diameter);
    }

    private Optional<LineString> search(InputSnapshot snapshot,Point start,Point goal,int diameter,
                                        RunMode mode,List<LineString> occupied,int graphDiameter) {
        RouteConstraintEngine constraints = constraintsFor(snapshot);
        Coordinate source = start.getCoordinate();
        Coordinate target = goal.getCoordinate();
        if (legal(constraints, source, target, diameter, occupied, source, mode, target)
                &&depthFeasible(snapshot,line(source,target),diameter,mode)) {
            return Optional.of(geometryFactory.createLineString(new Coordinate[] {source, target}));
        }

        List<Coordinate> approaches=buildingApproaches(snapshot,goal,diameter);
        approaches.removeIf(port->!legal(constraints,port,target,diameter,occupied,source,mode,target));
        boolean indoor=snapshot.restrictions.stream().anyMatch(r->"oks_existing".equals(r.type)&&r.geometry.covers(goal));
        if(indoor&&approaches.isEmpty())return Optional.empty();

        GraphTemplate template = staticGraphFor(snapshot, graphDiameter, mode);
        List<Coordinate> nodes = new ArrayList<>(template.nodes.size() + 2);
        nodes.add(new Coordinate(source));
        nodes.add(new Coordinate(target));
        for (Coordinate node : template.nodes) nodes.add(new Coordinate(node));
        int firstEntry=nodes.size();
        nodes.addAll(approaches);
        int afterEntry=nodes.size();
        // Global graph sampling must not remove the corners needed to get
        // around this particular building to its mandated entry wall.
        java.util.Set<String> nodeKeys=new java.util.HashSet<>();
        for(Coordinate node:nodes)nodeKeys.add(key(node));
        for(Coordinate escape:buildingEscapeNodes(snapshot,goal,graphDiameter,approaches))
            if(nodeKeys.add(key(escape)))nodes.add(escape);
        if(indoor)for(RestrictionBoundary boundary:boundariesFor(snapshot,graphDiameter))
            if(boundary.envelope.contains(target))for(Coordinate corner:boundary.coordinates)
                if(nodeKeys.add(key(corner)))nodes.add(new Coordinate(corner));
        List<Map<Integer, Double>> graph = copyStaticGraph(template, nodes.size());
        Map<Long, RouteAssessment> endpointAssessments = new HashMap<>();
        // Attach entry ports by an angular visibility sweep, rather
        // than testing a terminal against every obstacle vertex.  Apart from
        // matching its graph shape this avoids thousands of expensive
        // own-building checks for each tie candidate.
        for (int index : endpointNeighbours(nodes, 0)) {
            if(index>=firstEntry&&index<afterEntry)continue;
            addEdge(graph, endpointAssessments, nodes, 0, index, constraints, diameter, occupied, source, mode);
        }
        for (int index : endpointNeighbours(nodes, 1)) {
            addEdge(graph, endpointAssessments, nodes, 1, index, constraints, diameter, occupied, source, mode, target);
        }
        // Indoor goals cannot connect to arbitrary obstacle corners. Add exact
        // approach ports on rays through every equally nearest boundary point.
        for(int entry=firstEntry;entry<nodes.size();entry++) {
            addEdge(graph,endpointAssessments,nodes,1,entry,constraints,diameter,occupied,source,mode,target);
            if(entry<afterEntry) {
                Coordinate port=nodes.get(entry);
                addEdge(graph,endpointAssessments,nodes,0,entry,constraints,diameter,occupied,source,mode,target,port);
                List<Integer> regular=new ArrayList<>();
                for(int n=2;n<nodes.size();n++)if(n<firstEntry||n>=afterEntry)regular.add(n);
                regular.sort(Comparator.comparingDouble(n->nodes.get(n).distance(port)));
                int[] attempts=new int[VISIBILITY_SECTORS],accepted=new int[VISIBILITY_SECTORS];
                for(int neighbour:regular) {
                    int sector=sector(port,nodes.get(neighbour));
                    if(attempts[sector]++>=48||accepted[sector]>=2)continue;
                    if(addEdge(graph,endpointAssessments,nodes,entry,neighbour,constraints,diameter,
                            Collections.emptyList(),null,mode,target,port))accepted[sector]++;
                }
            } else {
                addEdge(graph,endpointAssessments,nodes,0,entry,constraints,diameter,occupied,source,mode);
                for(int neighbour:endpointNeighbours(nodes,entry))if(neighbour<firstEntry||neighbour>=afterEntry)
                    addEdge(graph,endpointAssessments,nodes,entry,neighbour,constraints,diameter,occupied,source,mode);
            }
        }
        Optional<LineString> route = shortestPath(nodes, graph, 0, 1, constraints, diameter, occupied, source, mode, target,firstEntry,afterEntry);
        if (route.isPresent()) {
            return route;
        }
        return Optional.empty();
    }

    private List<Coordinate> buildingApproaches(InputSnapshot snapshot,Point goal,int diameter) {
        String cacheKey=goal.getX()+":"+goal.getY()+":"+diameter;
        // Callers filter ports against the changing network, so return a separate list.
        return new ArrayList<>(approachesByGoal.computeIfAbsent(cacheKey,
                ignored->calculateBuildingApproaches(snapshot,goal,diameter)));
    }

    private List<Coordinate> calculateBuildingApproaches(InputSnapshot snapshot,Point goal,int diameter) {
        Map<String,Coordinate> approaches=new java.util.LinkedHashMap<>();
        double setback=buildingClearance(diameter)+RuleBook.byDn(diameter).pairWidth/2+.20;
        Coordinate terminal=goal.getCoordinate();
        for(InputSnapshot.Restriction restriction:snapshot.restrictions) {
            if(!"oks_existing".equals(restriction.type)||!restriction.geometry.covers(goal))continue;
            Geometry boundary=BuildingEntry.exteriorBoundary(restriction.geometry);
            double nearest=boundary.distance(goal);
            boundary.apply((GeometryComponentFilter)component->{
                if(!(component instanceof LineString))return;
                Coordinate[] ring=component.getCoordinates();
                for(int i=1;i<ring.length;i++) {
                    LineSegment wall=new LineSegment(ring[i-1],ring[i]);
                    Coordinate entry=wall.closestPoint(terminal);
                    double distance=entry.distance(terminal);
                    if(distance>nearest+1e-6)continue;
                    if(distance>1e-8) {
                        for(double offset:new double[]{setback}) {
                            Coordinate port=new Coordinate(entry.x+(entry.x-terminal.x)*offset/distance,
                                    entry.y+(entry.y-terminal.y)*offset/distance);
                            approaches.putIfAbsent(key(port),port);
                        }
                    } else if(wall.getLength()>1e-8) {
                        // A connection point on the facade has a zero-length
                        // internal lead; try both normals and validate the exterior.
                        double dx=(wall.p1.y-wall.p0.y)/wall.getLength()*setback;
                        double dy=(wall.p0.x-wall.p1.x)/wall.getLength()*setback;
                        for(int sign:new int[]{-1,1}) {
                            Coordinate port=new Coordinate(entry.x+sign*dx,entry.y+sign*dy);
                            if(!restriction.geometry.covers(geometryFactory.createPoint(port)))
                                approaches.putIfAbsent(key(port),port);
                        }
                    }
                }
            });
        }
        return new ArrayList<>(approaches.values());
    }

    private List<Coordinate> buildingEscapeNodes(InputSnapshot snapshot,Point goal,int diameter,List<Coordinate> ports) {
        StringBuilder cacheKey=new StringBuilder().append(goal.getX()).append(':').append(goal.getY()).append(':').append(diameter);
        for(Coordinate port:ports)cacheKey.append(':').append(port.x).append(':').append(port.y);
        return escapesByGoal.computeIfAbsent(cacheKey.toString(),ignored->calculateBuildingEscapeNodes(snapshot,goal,diameter,ports));
    }

    private List<Coordinate> calculateBuildingEscapeNodes(InputSnapshot snapshot,Point goal,int diameter,List<Coordinate> ports) {
        List<Coordinate> result=new ArrayList<>();
        double clearance=buildingClearance(diameter)+RuleBook.byDn(diameter).pairWidth/2;
        for(InputSnapshot.Restriction owner:snapshot.restrictions) {
            if(!"oks_existing".equals(owner.type)||!owner.geometry.covers(goal))continue;
            Geometry envelope=owner.geometry.buffer(clearance+.20);
            Envelope bounds=envelope.getEnvelopeInternal();
            double reach=2*Math.hypot(bounds.getWidth(),bounds.getHeight())+20;
            for(Coordinate port:ports) {
                double length=port.distance(goal.getCoordinate());
                double nx=(port.x-goal.getX())/length,ny=(port.y-goal.getY())/length;
                for(double degrees:new double[]{-90,-45,0,45,90}) {
                    double angle=Math.toRadians(degrees),dx=nx*Math.cos(angle)-ny*Math.sin(angle),dy=nx*Math.sin(angle)+ny*Math.cos(angle);
                    LineString ray=line(port,new Coordinate(port.x+dx*reach,port.y+dy*reach));
                    Geometry hit=ray.intersection(envelope);
                    org.locationtech.jts.linearref.LengthIndexedLine indexed=new org.locationtech.jts.linearref.LengthIndexedLine(ray);
                    for(int i=0;i<hit.getNumGeometries();i++) {
                        Geometry part=hit.getGeometryN(i);
                        if(part.isEmpty()||part.distance(geometryFactory.createPoint(port))>1e-5)continue;
                        double end=0;for(Coordinate c:part.getCoordinates())end=Math.max(end,indexed.project(c));
                        if(end+.05<reach)result.add(indexed.extractPoint(end+.05));
                    }
                }
            }
        }
        return result;
    }

    /** Builds the immutable visibility graph once per DN and mode. */
    private GraphTemplate staticGraphFor(InputSnapshot snapshot, int diameter, RunMode mode) {
        constraintsFor(snapshot);
        GraphKey key = new GraphKey(diameter, mode);
        return staticGraphs.computeIfAbsent(key, ignored -> {
            List<Coordinate> nodes = buildStaticNodes(snapshot, diameter);
            System.out.println("ROUTING graph DN=" + diameter + " nodes=" + nodes.size());
            List<Map<Integer, Double>> graph = buildStaticGraph(nodes, cachedConstraints, diameter, mode);
            staticGraphBuilds++;
            return new GraphTemplate(nodes, graph);
        });
    }

    private List<Coordinate> buildStaticNodes(InputSnapshot snapshot, int diameter) {
        Map<String, Coordinate> unique = new HashMap<>();
        for (RestrictionBoundary boundary : boundariesFor(snapshot, diameter)) {
            for (Coordinate coordinate : boundary.coordinates) unique.putIfAbsent(key(coordinate), new Coordinate(coordinate));
        }
        List<Coordinate> nodes = new ArrayList<>(unique.values());
        if (nodes.size() <= MAX_CANDIDATES - 2) return nodes;
        // Do not keep an arbitrary prefix of JTS buffer points.  A radial,
        // evenly-spaced sample retains detour vertices around the whole site,
        // including long barriers which used to disappear outside a corridor.
        double x = nodes.stream().mapToDouble(c -> c.x).average().orElse(0.0);
        double y = nodes.stream().mapToDouble(c -> c.y).average().orElse(0.0);
        nodes.sort(Comparator.comparingDouble((Coordinate c) -> Math.atan2(c.y - y, c.x - x))
                .thenComparingDouble(c -> c.distance(new Coordinate(x, y))));
        int limit = MAX_CANDIDATES - 2;
        List<Coordinate> sampled = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) sampled.add(nodes.get(index * nodes.size() / limit));
        return sampled;
    }

    private List<Map<Integer, Double>> buildStaticGraph(List<Coordinate> nodes, RouteConstraintEngine constraints,
                                                         int diameter, RunMode mode) {
        List<Map<Integer, Double>> graph = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) graph.add(new HashMap<>());
        Map<Long, RouteAssessment> assessed = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            List<Integer> nearest = nearestByDistance(nodes, index);
            List<List<Integer>> sectors = new ArrayList<>(VISIBILITY_SECTORS);
            for (int sector = 0; sector < VISIBILITY_SECTORS; sector++) sectors.add(new ArrayList<>());
            for (int candidate : nearest) sectors.get(sector(nodes.get(index), nodes.get(candidate))).add(candidate);
            for (List<Integer> candidates : sectors) {
                int attempts = 0;
                int accepted = 0;
                for (int candidate : candidates) {
                    if (attempts++ >= ATTEMPTS_PER_SECTOR || accepted >= NEIGHBORS_PER_SECTOR) break;
                    if (addEdge(graph, assessed, nodes, index, candidate, constraints, diameter,
                            Collections.emptyList(), null, mode)) accepted++;
                }
            }
        }
        return graph;
    }

    private List<Map<Integer, Double>> copyStaticGraph(GraphTemplate template, int totalSize) {
        List<Map<Integer, Double>> graph = new ArrayList<>(totalSize);
        for (int index = 0; index < totalSize; index++) graph.add(new HashMap<>());
        for (int from = 0; from < template.edges.size(); from++) {
            for (Map.Entry<Integer, Double> edge : template.edges.get(from).entrySet()) {
                graph.get(from + 2).put(edge.getKey() + 2, edge.getValue());
            }
        }
        return graph;
    }

    private List<Integer> nearestByDistance(List<Coordinate> nodes, int index) {
        Comparator<Integer> nearestFirst = Comparator.<Integer>comparingDouble(
                candidate -> nodes.get(index).distance(nodes.get(candidate))).thenComparingInt(candidate -> candidate);
        PriorityQueue<Integer> nearest = new PriorityQueue<>(FAST_NEAREST_CANDIDATES, nearestFirst.reversed());
        for (int candidate = 0; candidate < nodes.size(); candidate++) {
            if (candidate == index) continue;
            if (nearest.size() < FAST_NEAREST_CANDIDATES) nearest.add(candidate);
            else if (nearestFirst.compare(candidate, nearest.peek()) < 0) {
                nearest.remove();
                nearest.add(candidate);
            }
        }
        List<Integer> result = new ArrayList<>(nearest);
        result.sort(nearestFirst);
        return result;
    }

    private List<Integer> endpointNeighbours(List<Coordinate> nodes, int endpoint) {
        List<Integer> nearest = nearestByDistance(nodes, endpoint);
        List<List<Integer>> sectors = new ArrayList<>(VISIBILITY_SECTORS);
        for (int sector = 0; sector < VISIBILITY_SECTORS; sector++) sectors.add(new ArrayList<>());
        for (int candidate : nearest) if (candidate >= 2) sectors.get(sector(nodes.get(endpoint), nodes.get(candidate))).add(candidate);
        List<Integer> result = new ArrayList<>();
        for (List<Integer> candidates : sectors) {
            int count = 0;
            for (int candidate : candidates) {
                result.add(candidate);
                if (++count == ATTEMPTS_PER_SECTOR) break;
            }
        }
        return result;
    }

    private List<Coordinate> buildNodes(InputSnapshot snapshot, Coordinate source, Coordinate target, int diameter,
                                        List<LineString> occupied, double corridorPadding) {
        List<Coordinate> raw = new ArrayList<>();
        raw.add(new Coordinate(source));
        raw.add(new Coordinate(target));
        DnSpec spec = RuleBook.byDn(diameter);
        Envelope corridor = new Envelope(source, target);
        corridor.expandBy(corridorPadding);
        for (RestrictionBoundary boundary : boundariesFor(snapshot, diameter)) {
            if (!boundary.envelope.intersects(corridor)) {
                continue;
            }
            for (Coordinate coordinate : boundary.coordinates) {
                if (corridor.contains(coordinate)) {
                    raw.add(new Coordinate(coordinate));
                }
            }
        }
        if (occupied != null) {
            for (LineString line : occupied) {
                if (line.getEnvelopeInternal().intersects(corridor)) {
                    addBoundary(raw, line, spec.pairWidth + .20, corridor);
                }
            }
        }
        Map<String, Coordinate> unique = new HashMap<>();
        for (Coordinate coordinate : raw) {
            if (corridor.contains(coordinate) || coordinate.equals2D(source) || coordinate.equals2D(target)) {
                unique.putIfAbsent(key(coordinate), coordinate);
            }
        }
        unique.remove(key(source));
        unique.remove(key(target));
        List<Coordinate> candidates = new ArrayList<>(unique.values());
        candidates.sort(Comparator.comparingDouble((Coordinate point) -> distanceToSegment(point, source, target))
                .thenComparingDouble(point -> point.distance(source)).thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.y));
        if (candidates.size() > MAX_CANDIDATES - 2) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES - 2));
        }
        List<Coordinate> result = new ArrayList<>();
        result.add(new Coordinate(source));
        result.add(new Coordinate(target));
        result.addAll(candidates);
        return result;
    }

    private RouteConstraintEngine constraintsFor(InputSnapshot snapshot) {
        if (cachedSnapshot != snapshot) {
            cachedSnapshot = snapshot;
            cachedConstraints = new RouteConstraintEngine(snapshot);
            boundariesByDiameter.clear();
            staticGraphs.clear();
            routesWithoutNewLines.clear();
            entryAssessments.clear();
            approachesByGoal.clear();
            escapesByGoal.clear();
            staticGraphBuilds = 0;
        }
        return cachedConstraints;
    }

    private List<RestrictionBoundary> boundariesFor(InputSnapshot snapshot, int diameter) {
        constraintsFor(snapshot);
        return boundariesByDiameter.computeIfAbsent(diameter, ignored -> {
            DnSpec spec = RuleBook.byDn(diameter);
            List<RestrictionBoundary> boundaries = new ArrayList<>();
            for (InputSnapshot.Restriction restriction : snapshot.restrictions) {
                RestrictionRule rule = RestrictionRules.get(restriction.type);
                double clearance = "oks_existing".equals(restriction.type)
                        ? buildingClearance(diameter)
                        : rule == null ? 1.0 : rule.horizontalClearance;
                // Candidate vertices must be outside the very same envelope
                // that RouteConstraintEngine.assess uses.  The old fixed
                // one-metre value put all vertices around buildings inside a
                // five-to-nine-metre forbidden buffer.
                clearance += spec.pairWidth / 2.0 + RestrictionRules.objectHalfWidth(restriction.type) + .20;
                org.locationtech.jts.operation.buffer.BufferParameters parameters = new org.locationtech.jts.operation.buffer.BufferParameters();
                parameters.setJoinStyle(org.locationtech.jts.operation.buffer.BufferParameters.JOIN_MITRE);
                parameters.setEndCapStyle(org.locationtech.jts.operation.buffer.BufferParameters.CAP_SQUARE);
                Geometry buffered = org.locationtech.jts.operation.buffer.BufferOp.bufferOp(restriction.geometry, clearance, parameters);
                List<Coordinate> coordinates = new ArrayList<>();
                for (Coordinate coordinate : buffered.getCoordinates()) {
                    coordinates.add(new Coordinate(coordinate));
                }
                boundaries.add(new RestrictionBoundary(restriction.geometry.getEnvelopeInternal(), coordinates));
            }
            return boundaries;
        });
    }

    private void addBoundary(List<Coordinate> nodes, Geometry geometry, double distance, Envelope corridor) {
        Geometry buffered = geometry.buffer(distance);
        for (Coordinate coordinate : buffered.getCoordinates()) {
            if (corridor.contains(coordinate)) {
                nodes.add(new Coordinate(coordinate));
            }
        }
    }

    private static double buildingClearance(int diameter) {
        return diameter < 500 ? 5.0 : diameter <= 800 ? 7.0 : 9.0;
    }

    private List<Map<Integer, Double>> buildGraph(List<Coordinate> nodes, RouteConstraintEngine constraints,
                                                   int diameter, List<LineString> occupied, Coordinate source,
                                                   RunMode mode) {
        List<Map<Integer, Double>> graph = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            graph.add(new HashMap<>());
        }
        Map<Long, RouteAssessment> assessed = new HashMap<>();
        for (int i = 2; i < nodes.size(); i++) {
            addEdge(graph, assessed, nodes, 0, i, constraints, diameter, occupied, source, mode);
            addEdge(graph, assessed, nodes, 1, i, constraints, diameter, occupied, source, mode);
        }
        for (int i = 2; i < nodes.size(); i++) {
            List<List<Integer>> candidatesBySector = new ArrayList<>(VISIBILITY_SECTORS);
            for (int sector = 0; sector < VISIBILITY_SECTORS; sector++) {
                candidatesBySector.add(new ArrayList<>());
            }
            final int index = i;
            Comparator<Integer> nearestFirst = Comparator.<Integer>comparingDouble(
                    candidate -> nodes.get(index).distance(nodes.get(candidate))).thenComparingInt(candidate -> candidate);
            PriorityQueue<Integer> nearest = new PriorityQueue<>(FAST_NEAREST_CANDIDATES, nearestFirst.reversed());
            for (int j = 2; j < nodes.size(); j++) {
                if (j == i) {
                    continue;
                }
                if (nearest.size() < FAST_NEAREST_CANDIDATES) {
                    nearest.add(j);
                } else if (nearestFirst.compare(j, nearest.peek()) < 0) {
                    nearest.remove();
                    nearest.add(j);
                }
            }
            List<Integer> nearestSorted = new ArrayList<>(nearest);
            nearestSorted.sort(nearestFirst);
            for (int candidate : nearestSorted) {
                candidatesBySector.get(sector(nodes.get(i), nodes.get(candidate))).add(candidate);
            }
            for (List<Integer> sectorCandidates : candidatesBySector) {
                int attempted = 0;
                int accepted = 0;
                for (int candidate : sectorCandidates) {
                    if (attempted++ >= ATTEMPTS_PER_SECTOR || accepted >= NEIGHBORS_PER_SECTOR) {
                        break;
                    }
                    if (addEdge(graph, assessed, nodes, i, candidate, constraints, diameter, occupied, source, mode)) {
                        accepted++;
                    }
                }
            }
        }
        return graph;
    }

    private boolean addEdge(List<Map<Integer, Double>> graph, Map<Long, RouteAssessment> assessed,
                            List<Coordinate> nodes, int left, int right, RouteConstraintEngine constraints,
                            int diameter, List<LineString> occupied, Coordinate source, RunMode mode) {
        return addEdge(graph,assessed,nodes,left,right,constraints,diameter,occupied,source,mode,null);
    }

    private boolean addEdge(List<Map<Integer, Double>> graph, Map<Long, RouteAssessment> assessed,
                            List<Coordinate> nodes, int left, int right, RouteConstraintEngine constraints,
                            int diameter, List<LineString> occupied, Coordinate source, RunMode mode,
                            Coordinate allowedBuildingTerminal) {
        return addEdge(graph,assessed,nodes,left,right,constraints,diameter,occupied,source,mode,allowedBuildingTerminal,null);
    }

    private boolean addEdge(List<Map<Integer, Double>> graph, Map<Long, RouteAssessment> assessed,
                            List<Coordinate> nodes, int left, int right, RouteConstraintEngine constraints,
                            int diameter, List<LineString> occupied, Coordinate source, RunMode mode,
                            Coordinate allowedBuildingTerminal,Coordinate entryPort) {
        long edge = pair(left, right);
        RouteAssessment assessment = assessed.get(edge);
        if (assessment == null) {
            if(entryPort!=null&&source==null&&occupied.isEmpty()) {
                String cacheKey=key(nodes.get(left))+":"+key(nodes.get(right))+":"+key(allowedBuildingTerminal)+":"+diameter+":"+mode;
                assessment=entryAssessments.computeIfAbsent(cacheKey,key->constraints.assess(line(nodes.get(left),nodes.get(right)),
                        diameter,occupied,null,mode,allowedBuildingTerminal,entryPort));
            } else assessment = constraints.assess(line(nodes.get(left), nodes.get(right)), diameter, occupied, source, mode,
                    allowedBuildingTerminal,entryPort);
            assessed.put(edge, assessment);
        }
        if (!assessment.feasible) {
            return false;
        }
        double weight = nodes.get(left).distance(nodes.get(right)) * assessment.multiplier;
        graph.get(left).put(right, weight);
        graph.get(right).put(left, weight);
        return true;
    }

    private Optional<LineString> shortestPath(List<Coordinate> nodes, List<Map<Integer, Double>> graph,
                                               int source, int target, RouteConstraintEngine constraints,
                                               int diameter, List<LineString> occupied, Coordinate allowedTouch,
                                               RunMode mode, Coordinate allowedBuildingTerminal,int firstEntry,int afterEntry) {
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator.comparingDouble((State value) -> value.priority)
                .thenComparingInt(value -> value.node).thenComparingInt(value -> value.prior));
        Map<StateKey, Double> distance = new HashMap<>();
        Map<StateKey, StateKey> previous = new HashMap<>();
        // Static geometry has already been checked when the template was
        // built.  This small per-search cache is the barrier-edge
        // cache: it only answers whether a static edge conflicts with the
        // network that has been placed earlier in this candidate plan.
        Map<Long, Boolean> barrierAssessments = new HashMap<>();
        StateKey first = new StateKey(source, -1);
        distance.put(first, 0.0);
        queue.add(new State(source, -1, 0.0, nodes.get(source).distance(nodes.get(target))));
        while (!queue.isEmpty()) {
            State current = queue.remove();
            StateKey key = new StateKey(current.node, current.prior);
            if (current.cost > distance.getOrDefault(key, Double.POSITIVE_INFINITY) + 1e-9) continue;
            if (current.node == target) {
                List<Coordinate> path=new ArrayList<>();
                for(StateKey step=key;step!=null;step=previous.get(step))path.add(nodes.get(step.node));
                Collections.reverse(path);
                List<Coordinate> simplified=simplify(path,constraints,diameter,occupied,allowedTouch,mode,allowedBuildingTerminal);
                LineString candidate=geometryFactory.createLineString(simplified.toArray(new Coordinate[0]));
                if(depthFeasible(cachedSnapshot,candidate,diameter,mode))return Optional.of(candidate);
                candidate=geometryFactory.createLineString(path.toArray(new Coordinate[0]));
                if(depthFeasible(cachedSnapshot,candidate,diameter,mode))return Optional.of(candidate);
                continue;
            }
            for (Map.Entry<Integer, Double> next : graph.get(current.node).entrySet()) {
                int neighbour = next.getKey();
                if(current.node>=firstEntry&&current.node<afterEntry&&neighbour!=target)continue;
                if (neighbour == current.prior || (current.prior >= 0
                        && reverses(nodes.get(current.prior), nodes.get(current.node), nodes.get(neighbour)))) continue;
                if (occupied != null && !occupied.isEmpty()) {
                    long edge = pair(current.node, neighbour);
                    Boolean blocked = barrierAssessments.get(edge);
                    if (blocked == null) {
                        blocked = blockedByNewLines(line(nodes.get(current.node), nodes.get(neighbour)), occupied, allowedTouch);
                        barrierAssessments.put(edge, blocked);
                    }
                    if (blocked) continue;
                }
                StateKey nextKey = new StateKey(neighbour, current.node);
                double nextCost = current.cost + next.getValue()
                        + bendPenalty(nodes, current.prior, current.node, neighbour);
                if (nextCost >= distance.getOrDefault(nextKey, Double.POSITIVE_INFINITY) - 1e-9) continue;
                distance.put(nextKey, nextCost);
                previous.put(nextKey, key);
                queue.add(new State(neighbour, current.node, nextCost,
                        nextCost + nodes.get(neighbour).distance(nodes.get(target))));
            }
        }
        return Optional.empty();
    }

    private boolean depthFeasible(InputSnapshot snapshot,LineString line,int diameter,RunMode mode) {
        return mode!=RunMode.DEPTH||new ru.lct.teplokontur.engineering.SegmentCostModel().feasibleDepth(snapshot,line,diameter);
    }

    private List<Coordinate> simplify(List<Coordinate> input, RouteConstraintEngine constraints, int diameter,
                                      List<LineString> occupied, Coordinate allowedTouch, RunMode mode,
                                      Coordinate allowedBuildingTerminal) {
        List<Coordinate> result = new ArrayList<>(input);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int index = 0; index + 2 < result.size(); index++) {
                if (!legal(constraints, result.get(index), result.get(index + 2), diameter, occupied, allowedTouch,
                        mode, allowedBuildingTerminal)) continue;
                if (index > 0 && reverses(result.get(index - 1), result.get(index), result.get(index + 2))) continue;
                if (index + 3 < result.size() && reverses(result.get(index), result.get(index + 2), result.get(index + 3))) continue;
                result.remove(index + 1);
                changed = true;
                break;
            }
        }
        return result;
    }

    private boolean blockedByNewLines(LineString segment, List<LineString> occupied, Coordinate allowedTouch) {
        for (LineString existing : occupied) {
            if (!segment.getEnvelopeInternal().intersects(existing.getEnvelopeInternal())) continue;
            Geometry intersection = segment.intersection(existing);
            if (intersection.isEmpty()) continue;
            if (intersection.getDimension() >= 1 && intersection.getLength() > .05) return true;
            for (Coordinate coordinate : intersection.getCoordinates())
                if (allowedTouch == null || coordinate.distance(allowedTouch) >= .05) return true;
        }
        return false;
    }

    private boolean legal(RouteConstraintEngine constraints, Coordinate a, Coordinate b, int diameter,
                          List<LineString> occupied, Coordinate allowedTouch, RunMode mode) {
        return constraints.assess(line(a, b), diameter, occupied, allowedTouch, mode).feasible;
    }

    private boolean legal(RouteConstraintEngine constraints, Coordinate a, Coordinate b, int diameter,
                          List<LineString> occupied, Coordinate allowedTouch, RunMode mode,
                          Coordinate allowedBuildingTerminal) {
        return constraints.assess(line(a,b),diameter,occupied,allowedTouch,mode,allowedBuildingTerminal).feasible;
    }

    private LineString line(Coordinate a, Coordinate b) {
        return geometryFactory.createLineString(new Coordinate[] {a, b});
    }

    /** The heading check rejects only a backwards turn (negative dot product). */
    private static boolean reverses(Coordinate before, Coordinate current, Coordinate after) {
        return (current.x - before.x) * (after.x - current.x)
                + (current.y - before.y) * (after.y - current.y) < -1e-7;
    }

    private static double bendPenalty(List<Coordinate> nodes, int prior, int current, int next) {
        if (prior < 0) {
            return 0.0;
        }
        Coordinate a = nodes.get(prior);
        Coordinate b = nodes.get(current);
        Coordinate c = nodes.get(next);
        double first = a.distance(b);
        double second = b.distance(c);
        if (first < 1e-9 || second < 1e-9) {
            return 0.0;
        }
        double dot = ((b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y)) / (first * second);
        return dot < Math.cos(Math.toRadians(5.0)) ? BEND_PENALTY_METERS : 0.0;
    }

    private static int sector(Coordinate origin, Coordinate point) {
        double angle = Math.atan2(point.y - origin.y, point.x - origin.x) + Math.PI;
        return Math.min(VISIBILITY_SECTORS - 1, (int) (angle * VISIBILITY_SECTORS / (2.0 * Math.PI)));
    }

    private static String key(Coordinate coordinate) {
        return Math.round(coordinate.x * 1_000_000.0) + ":" + Math.round(coordinate.y * 1_000_000.0);
    }

    private static long pair(int first, int second) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        return ((long) low << 32) | (high & 0xffffffffL);
    }

    private static double distanceToSegment(Coordinate point, Coordinate start, Coordinate end) {
        double x = end.x - start.x;
        double y = end.y - start.y;
        double square = x * x + y * y;
        if (square < 1e-12) return point.distance(start);
        double t = Math.max(0.0, Math.min(1.0, ((point.x - start.x) * x + (point.y - start.y) * y) / square));
        return point.distance(new Coordinate(start.x + t * x, start.y + t * y));
    }

    private static final class State {
        private final int node;
        private final int prior;
        private final double cost;
        private final double priority;
        private State(int node, int prior, double cost, double priority) {
            this.node = node; this.prior = prior; this.cost = cost; this.priority = priority;
        }
    }

    private static final class RestrictionBoundary {
        private final Envelope envelope;
        private final List<Coordinate> coordinates;

        private RestrictionBoundary(Envelope envelope, List<Coordinate> coordinates) {
            this.envelope = new Envelope(envelope);
            this.coordinates = coordinates;
        }
    }

    /** Package-visible solely for the regression proving scenario-level reuse. */
    int staticGraphBuildCount() { return staticGraphBuilds; }

    private static final class GraphKey {
        private final int diameter;
        private final RunMode mode;
        private GraphKey(int diameter, RunMode mode) { this.diameter = diameter; this.mode = mode; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof GraphKey)) return false;
            GraphKey other = (GraphKey) value;
            return diameter == other.diameter && mode == other.mode;
        }
        @Override public int hashCode() { return 31 * diameter + mode.hashCode(); }
    }

    private static final class GraphTemplate {
        private final List<Coordinate> nodes;
        private final List<Map<Integer, Double>> edges;
        private GraphTemplate(List<Coordinate> nodes, List<Map<Integer, Double>> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    private static final class StateKey {
        private final int node;
        private final int prior;
        private StateKey(int node, int prior) { this.node = node; this.prior = prior; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof StateKey)) return false;
            StateKey other = (StateKey) value;
            return node == other.node && prior == other.prior;
        }
        @Override public int hashCode() { return 31 * node + prior; }
    }
}

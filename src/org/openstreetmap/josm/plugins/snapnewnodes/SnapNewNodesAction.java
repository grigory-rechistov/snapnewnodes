/*
 * This file is part of SnapNewNodes plugin
 * Copyright (c) 2019 Grigory Rechistov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 2 or later
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package org.openstreetmap.josm.plugins.snapnewnodes;

import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.toRadians;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.HelpAwareOptionPane;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.HelpAwareOptionPane.ButtonSpec;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.bugreport.DebugTextDisplay;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Pair;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.Utils;

public final class SnapNewNodesAction extends JosmAction {

    public SnapNewNodesAction() {
        super(tr("Snap New Nodes"), "simplify", tr("Snap close nodes of way(s)"),
                Shortcut.registerShortcut("tools:snapnewnodes", tr("Tool: {0}", tr("Snap New Nodes")), KeyEvent.VK_Z, Shortcut.CTRL_SHIFT),
                true, "snapnewnodes", true);
    }

    private List<Bounds> getCurrentEditBounds() {
        return MainApplication.getLayerManager().getEditLayer().data.getDataSourceBounds();
    }

    private static boolean isInBounds(final Node node, final List<Bounds> bounds) {
        for (final Bounds b : bounds) {
            if (b.contains(node.getCoor())) {
                return true;
            }
        }
        return false;
    }

    private static boolean confirmWayWithNodesOutsideBoundingBox() {
        final ButtonSpec[] options = new ButtonSpec[] { new ButtonSpec(tr("Yes, delete nodes"), ImageProvider.get("ok"), tr("Delete nodes outside of downloaded data regions"), null),
                new ButtonSpec(tr("No, abort"), ImageProvider.get("cancel"), tr("Cancel operation"), null) };
        return 0 == HelpAwareOptionPane.showOptionDialog(
                MainApplication.getMainFrame(),
                "<html>" + trn("The selected way has nodes outside of the downloaded data region.", "The selected ways have nodes outside of the downloaded data region.", 
                        MainApplication.getLayerManager().getEditDataSet().getSelectedWays().size())
                + "<br>" + tr("This can lead to nodes being deleted accidentally.") + "<br>" + tr("Do you want to delete them anyway?") + "</html>",
                tr("Delete nodes outside of data regions?"), JOptionPane.WARNING_MESSAGE, null, // no special icon
                options, options[0], null);
    }

    private void alertSelectAtLeastOneWay() {
        HelpAwareOptionPane.showOptionDialog(MainApplication.getMainFrame(), tr("Please select at least one way to simplify."), tr("Warning"), JOptionPane.WARNING_MESSAGE, null);
    }

    private boolean confirmSimplifyManyWays(final int numWays) {
        final ButtonSpec[] options = new ButtonSpec[] { new ButtonSpec(tr("Yes"), ImageProvider.get("ok"), tr("Simplify all selected ways"), null),
                new ButtonSpec(tr("Cancel"), ImageProvider.get("cancel"), tr("Cancel operation"), null) };
        return 0 == HelpAwareOptionPane.showOptionDialog(MainApplication.getMainFrame(), tr("The selection contains {0} ways. Are you sure you want to simplify them all?", numWays), 
                tr("Simplify ways?"),
                JOptionPane.WARNING_MESSAGE, null, // no special icon
                options, options[0], null);
    }

    //@Override
    public void old_actionPerformed(final ActionEvent e) { // TODO delete
        final Collection<OsmPrimitive> selection = getLayerManager().getEditDataSet().getSelected();

        final List<Bounds> bounds = getCurrentEditBounds();
        for (final OsmPrimitive prim : selection) {
            if (prim instanceof Way && bounds.size() > 0) {
                final Way way = (Way) prim;
                // We check if each node of each way is at least in one download
                // bounding box. Otherwise nodes may get deleted that are necessary by
                // unloaded ways (see Ticket #1594)
                for (final Node node : way.getNodes()) {
                    if (!isInBounds(node, bounds)) {
                        if (!confirmWayWithNodesOutsideBoundingBox()) {
                            return;
                        }
                        break;
                    }
                }
            }
        }
        final Collection<Way> ways = Utils.filteredCollection(selection, Way.class);
        if (ways.isEmpty()) {
            alertSelectAtLeastOneWay();
            return;
        } else if (ways.size() > 10) {
            if (!confirmSimplifyManyWays(ways.size())) {
                return;
            }
        }

        final List<Node> nodesToDelete = new ArrayList<>(); // can contain duplicate instances

        for (final Way way : ways) {
            addNodesToDelete(nodesToDelete, way);
        }

        final Map<Node, Integer> nodeCountMap = new HashMap<>();
        for (final Node node : nodesToDelete) {
            Integer count = nodeCountMap.get(node);
            if (count == null) {
                count = 0;
            }
            nodeCountMap.put(node, ++count);
        }

        final Collection<Node> nodesReallyToRemove = new ArrayList<>();

        for (final Entry<Node, Integer> entry : nodeCountMap.entrySet()) {
            final Node node = entry.getKey();
            final Integer count = entry.getValue();

            if (!node.isTagged() && node.getReferrers().size() == count) {
                nodesReallyToRemove.add(node);
            }
        }

        final Collection<Command> allCommands = new ArrayList<>();

        if (!nodesReallyToRemove.isEmpty()) {
            for (final Way way : ways) {
                final List<Node> nodes = way.getNodes();
                final boolean closed = nodes.get(0).equals(nodes.get(nodes.size() - 1));
                if (closed) {
                    nodes.remove(nodes.size() - 1);
                }

                if (nodes.removeAll(nodesReallyToRemove)) {
                    if (closed) {
                        nodes.add(nodes.get(0));
                    }

                    final Way newWay = new Way(way);
                    newWay.setNodes(nodes);
                    allCommands.add(new ChangeCommand(way, newWay));
                }
            }

            allCommands.add(new DeleteCommand(nodesReallyToRemove));
        }

        final Collection<Command> avgCommands = averageNearbyNodes(ways, nodesReallyToRemove);
        if (avgCommands != null && !avgCommands.isEmpty()) {
            allCommands.add(new SequenceCommand(tr("average nearby nodes"), avgCommands));
        }

        if (!allCommands.isEmpty()) {
            final SequenceCommand rootCommand = new SequenceCommand(trn("Simplify {0} way", "Simplify {0} ways", allCommands.size(), allCommands.size()), allCommands);
            UndoRedoHandler.getInstance().add(rootCommand);
            MainApplication.getMap().repaint();
        }
    }
    
    @Override
    public void actionPerformed(final ActionEvent e) {
        Logging.debug("Snap Action started");
        int totalMovedNodes = 0;
        final double threshold = Config.getPref().getDouble(SnapNewNodesPreferenceSetting.DIST_THRESHOLD, 10);
        /* Post-invariants of this method:
         * No new nodes added to selection
         * No nodes belonging to more than one way are moved
         * Some nodes in selection may change coordinates
         * New nodes may be added into middle positions of snap candidates ways
         * Every node is moved at most once
         * TODO after this method has finished, nodes with duplicate coordinates 
         * may be present.
         */
        
        final Collection<OsmPrimitive> selection = getLayerManager().getEditDataSet().getSelected();
        Collection<Way> moveWayCandidates = Utils.filteredCollection(selection, Way.class);

        ArrayList<Node> movableNodes = new ArrayList<>();
        for (final Way w : moveWayCandidates) {
            Collection<Node> nodes = w.getNodes();
            // TODO: do not iterate over the last node if it is the same as the first one
            for (Node n: nodes) {
                boolean nodeBelongsToManyWays = nodeGluesWays(n); 
                // TODO other conditions blocking moves: tags on a way, ID > 0 etc
                if (!nodeBelongsToManyWays) {
                    movableNodes.add(n);
                }
            }
        }
        if (movableNodes.isEmpty()) {
            alertSelectAtLeastOneWay();
            return;
        }
        Logging.debug("Working with {0} movable nodes", movableNodes.size());
        
        /* Determine ways to which snapping is possible */
        final List<String> acceptedNatural = Arrays.asList("wood", "scrub", 
                "heath", "moor", "grassland", "fell", "water", "wetland", 
                "beach", "coastline");
        final List<String> ignoredLanduses = Arrays.asList("military");
        final List<String> acceptedWaterways = Arrays.asList("riverbank", "dock");
        
        final Collection<Way> allWays = getLayerManager().getEditDataSet().getWays();
        ArrayList<Way> candidateWays = new ArrayList<>();
        for (final Way w : allWays) {
            boolean isNatural = w.get("natural") != null
                                && acceptedNatural.contains(w.get("natural"));
            boolean isLanduse = (w.get("landuse") != null) 
                                 && !ignoredLanduses.contains(w.get("landuse")); 
            boolean isWaterway = w.get("waterway") != null
                                 && acceptedWaterways.contains(w.get("waterway"));      
            
            /* TODO put this and other linear objects as lowest priority in a 
               separate filtering pass */
            boolean isHighway = w.get("highway") != null;
            
            boolean accepted = isNatural || isLanduse || isWaterway || isHighway;
            if (accepted) {
                candidateWays.add(w);
            }
        }
       
        Logging.debug("Working with {0} candidate ways", candidateWays.size());
        
        for (final Way cw : candidateWays) {
            Logging.debug("Snapping to candidate way id {0} ({1} nodes)", cw.getId(), cw.getNodesCount());
            Set<Node> allRepositionedNodes = new HashSet<>(); // TODO why a set? a list would be clearer 
            for (Node n: movableNodes) { /* Try to find a place to snap n to cw */
                Logging.debug("Trying to snap {0}", n);
                if (n.getParentWays().contains(cw)) { // TODO is this a cheap call?
                    Logging.debug("Node is already on this way", n);
                    continue; /* n is already on cw */
                }
                if (!cw.getBBox().bounds(n.getCoor())) { // TODO is it efficient?
                    Logging.debug("Out of bounds for current way", n);
                    continue; /* n is outside the bounding box of cw */
                }
                final List<Node> candidateNodes = cw.getNodes();
//                Pair<Integer, Node> insertionPoints = new ArrayList<>();
                int insertionPosition = -1;
                for (int k = 0; k < candidateNodes.size()-1; k ++) {
                    Pair<LatLon, Double> res = calculateNearestPointOnSegment(n,
                                                    candidateNodes.get(k),
                                                    candidateNodes.get(k+1));
                    LatLon newCoords = res.a; 
                    double distance = res.b;
                    if (distance < threshold) {
                        /* Two things will happen:
                         1. n to be moved to newCoords,
                         2. n to be inserted into cw at k+1 position */
                        Logging.debug("Node {0} is close to the way at coords {1}", n.toString(), newCoords.toString());
                        totalMovedNodes ++;
                        n.setCoor(newCoords);
                        allRepositionedNodes.add(n);
                        insertionPosition = k+1;
                        break; // new home for n at cw has been found
                    }
                }
                /* insert new point in cw */
                if (insertionPosition >= 0) {
                    cw.addNode(insertionPosition, n);
                }
                
            }
            /* All nodes that have been moved after current iteration should be
               excluded from movableNodes to prevent further wandering */
            for (Node ntd: allRepositionedNodes) {
                movableNodes.remove(ntd);
            }
        }

        
        MainApplication.getMap().repaint();
        
        String infoMsg = String.format(tr("Snapped %d nodes"), totalMovedNodes);
        new Notification(infoMsg).setIcon(JOptionPane.INFORMATION_MESSAGE).show();
        Logging.debug(infoMsg);
        
        // TODO add Undo/Redo buffer entries
    }

    /* Finds a point on line segment [b, c] that is closest to a.
     * Returns new coords and distance to it */
    private static Pair<LatLon, Double> calculateNearestPointOnSegment(final Node a,
                                                    final Node b, final Node c) {
        // TODO Write a proper algorithm to find a point on the segment, not
        // just one of the end points
        LatLon a_p = a.getCoor();
        LatLon b_p = b.getCoor();
        LatLon c_p = c.getCoor();
         
        double dist1 = a_p.greatCircleDistance(b_p);
        double dist2 = a_p.greatCircleDistance(c_p);
        
        Pair<LatLon, Double> result = null;
        
        if (dist1 < dist2) {
            result = new Pair<>(b_p, dist1);
        } else {
            result = new Pair<>(c_p, dist2);
        }
        Logging.debug("measuring {0} to [{1}, {2}]: distance is {3}", a_p, b_p, c_p, result.b);
        return result;
    }
    
    private static boolean nodeGluesWays(final Node node) {
        Set<Node> referenceNeighbours = null;
        for (final OsmPrimitive ref : node.getReferrers()) {
            if (ref.getType() == OsmPrimitiveType.WAY) {
                final Way way = ((Way) ref);
                final Set<Node> neighbours = way.getNeighbours(node);
                if (referenceNeighbours == null) {
                    referenceNeighbours = neighbours;
                } else if (!referenceNeighbours.containsAll(neighbours)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    // average nearby nodes
    private static Collection<Command> averageNearbyNodes(final Collection<Way> ways, final Collection<Node> nodesAlreadyDeleted) {
        final double mergeThreshold = 0.2;

        final Map<Node, LatLon> coordMap = new HashMap<>();
        for (final Way way : ways) {
            for (final Node n : way.getNodes()) {
                coordMap.put(n, n.getCoor());
            }
        }

        coordMap.keySet().removeAll(nodesAlreadyDeleted);

        for (final Way w : ways) {
            final List<Node> nodes = w.getNodes();

            final Node lastNode = nodes.get(nodes.size() - 1);
            final boolean closed = nodes.get(0).equals(lastNode);
            if (closed) {
                nodes.remove(lastNode);
            }

            nodes.retainAll(coordMap.keySet()); // removes already deleted nodes

            while (true) {
                double minDist = Double.POSITIVE_INFINITY;
                Node node1 = null;
                Node node2 = null;

                final int len = nodes.size();
                if (len == 0) {
                    break;
                }

                // find smallest distance
                for (int i = 0; i <= len; i++) {
                    final Node n1 = nodes.get(i % len);
                    final Node n2 = nodes.get((i + 1) % len);

                    if (n1.isTagged() || n2.isTagged()) {
                        continue;
                    }

                    // test if both nodes are on the same ways
                    final List<OsmPrimitive> referrers = n1.getReferrers();
                    if (!ways.containsAll(referrers)) {
                        continue;
                    }

                    final List<OsmPrimitive> referrers2 = n2.getReferrers();
                    if (!ways.containsAll(referrers2)) {
                        continue;
                    }

                    // test if both nodes have same parents
                    if (!referrers.containsAll(referrers2) || !referrers2.containsAll(referrers)) {
                        continue;
                    }

                    final LatLon a = coordMap.get(n1);
                    final LatLon b = coordMap.get(n2);
                    
                    if (a != null && b != null) {
                        final double dist = a.greatCircleDistance(b);
                        if (dist < minDist && dist < mergeThreshold) {
                            minDist = dist;
                            node1 = n1;
                            node2 = n2;
                        }
                    }
                }

                if (node1 == null || node2 == null) {
                    break;
                }

                final LatLon coord = coordMap.get(node1).getCenter(coordMap.get(node2));
                coordMap.put(node1, coord);

                nodes.remove(node2);
                coordMap.remove(node2);
            }
        }

        final Collection<Command> commands = new ArrayList<>();
        final Set<Node> nodesToDelete2 = new HashSet<>();
        for (final Way way : ways) {
            final List<Node> nodesToDelete = way.getNodes();
            nodesToDelete.removeAll(nodesAlreadyDeleted);
            if (nodesToDelete.removeAll(coordMap.keySet())) {
                nodesToDelete2.addAll(nodesToDelete);
                final Way newWay = new Way(way);
                final List<Node> nodes = way.getNodes();
                final boolean closed = nodes.get(0).equals(nodes.get(nodes.size() - 1));
                if (closed) {
                    nodes.remove(nodes.size() - 1);
                }
                nodes.retainAll(coordMap.keySet());
                if (closed) {
                    nodes.add(nodes.get(0));
                }

                newWay.setNodes(nodes);
                if (!way.getNodes().equals(nodes)) {
                    commands.add(new ChangeCommand(way, newWay));
                }
            }
        }

        if (!nodesToDelete2.isEmpty()) {
            commands.add(new DeleteCommand(nodesToDelete2));
        }

        for (final Entry<Node, LatLon> entry : coordMap.entrySet()) {
            final Node node = entry.getKey();
            final LatLon coord = entry.getValue();
            if (!node.getCoor().equals(coord)) {
                commands.add(new MoveCommand(node, coord));
            }
        }

        return commands;
    }

    private static void addNodesToDelete(final Collection<Node> nodesToDelete, final Way w) {
        final double angleThreshold = 10;
        final double angleFactor = 1.0;
        final double areaThreshold = 5.0;
        final double areaFactor = 1.0;
        final double distanceThreshold = Config.getPref().getDouble(SnapNewNodesPreferenceSetting.DIST_THRESHOLD, 3);
        final double distanceFactor = 3;

        final List<Node> nodes = w.getNodes();
        final int size = nodes.size();

        if (size == 0) {
            return;
        }

        final boolean closed = nodes.get(0).equals(nodes.get(size - 1));

        if (closed) {
            nodes.remove(size - 1); // remove end node ( = start node)
        }

        // remove nodes within threshold

        final List<Double> weightList = new ArrayList<>(nodes.size()); // weight cache
        for (int i = 0; i < nodes.size(); i++) {
            weightList.add(null);
        }

        while (true) {
            Node prevNode = null;
            LatLon coord1 = null;
            LatLon coord2 = null;
            int prevIndex = -1;

            double minWeight = Double.POSITIVE_INFINITY;
            Node bestMatch = null;

            final int size2 = nodes.size();

            if (size2 == 0) {
                break;
            }

            for (int i = 0, len = size2 + (closed ? 2 : 1); i < len; i++) {
                final int index = i % size2;

                final Node n = nodes.get(index);
                final LatLon coord3 = n.getCoor();

                if (coord1 != null) {
                    final double weight;

                    if (weightList.get(prevIndex) == null) {
                        final double angleWeight = computeConvectAngle(coord1, coord2, coord3) / angleThreshold;
                        final double areaWeight = computeArea(coord1, coord2, coord3) / areaThreshold;
                        final double distanceWeight = Math.abs(crossTrackError(coord1, coord2, coord3)) / distanceThreshold;

                        weight = (!closed && i == len - 1) || // don't remove last node of the not closed way
                                nodeGluesWays(prevNode) ||
                                angleWeight > 1.0 || areaWeight > 1.0 || distanceWeight > 1.0 ? Double.POSITIVE_INFINITY :
                                angleWeight * angleFactor + areaWeight * areaFactor + distanceWeight * distanceFactor;

                        weightList.set(prevIndex, weight);
                    } else {
                        weight = weightList.get(prevIndex);
                    }

                    if (weight < minWeight) {
                        minWeight = weight;
                        bestMatch = prevNode;
                    }
                }

                coord1 = coord2;
                coord2 = coord3;
                prevNode = n;
                prevIndex = index;
            }

            if (bestMatch == null) {
                break;
            }

            final int index = nodes.indexOf(bestMatch);

            weightList.set((index - 1 + size2) % size2, null);
            weightList.set((index + 1 + size2) % size2, null);
            weightList.remove(index);
            nodes.remove(index);
        }

        final HashSet<Node> delNodes = new HashSet<>(w.getNodes());
        delNodes.removeAll(nodes);

        nodesToDelete.addAll(delNodes);
    }

    public static double computeConvectAngle(final LatLon coord1, final LatLon coord2, final LatLon coord3) {
        final double angle =  Math.abs(heading(coord2, coord3) - heading(coord1, coord2));
        return Math.toDegrees(angle < Math.PI ? angle : 2 * Math.PI - angle);
    }

    public static double computeArea(final LatLon coord1, final LatLon coord2, final LatLon coord3) {
        final double a = coord1.greatCircleDistance(coord2);
        final double b = coord2.greatCircleDistance(coord3);
        final double c = coord3.greatCircleDistance(coord1);

        final double p = (a + b + c) / 2.0;

        final double q = p * (p - a) * (p - b) * (p - c); // I found this negative in one case (:-o) when nodes were in line on a small area
        return q < 0.0 ? 0.0 : Math.sqrt(q);
    }

    public static double R = 6378135;

    public static double crossTrackError(final LatLon l1, final LatLon l2, final LatLon l3) {
        return R * Math.asin(sin(l1.greatCircleDistance(l2) / R) * sin(heading(l1, l2) - heading(l1, l3)));
    }

    public static double heading(final LatLon a, final LatLon b) {
        double hd = Math.atan2(sin(toRadians(a.lon() - b.lon())) * cos(toRadians(b.lat())),
                cos(toRadians(a.lat())) * sin(toRadians(b.lat())) -
                sin(toRadians(a.lat())) * cos(toRadians(b.lat())) * cos(toRadians(a.lon() - b.lon())));
        hd %= 2 * Math.PI;
        if (hd < 0) {
            hd += 2 * Math.PI;
        }
        return hd;
    }

    @Override
    protected void updateEnabledState() {
        if (getLayerManager().getEditDataSet() == null) {
            setEnabled(false);
        } else {
            updateEnabledState(getLayerManager().getEditDataSet().getSelected());
        }
    }

    @Override
    protected void updateEnabledState(final Collection<? extends OsmPrimitive> selection) {
        setEnabled(selection != null && !selection.isEmpty());
    }
}

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

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.HelpAwareOptionPane;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
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

    private void alertNoMovableNodesFound() {
        HelpAwareOptionPane.showOptionDialog(MainApplication.getMainFrame(), 
                tr("No nodes that can be moved were present in selected ways."), 
                tr("Warning"), JOptionPane.WARNING_MESSAGE, null);
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
        // TODO allow snapping individual nodes, not just nodes on ways

        ArrayList<Node> movableNodes = new ArrayList<>();
        for (final Way w : moveWayCandidates) {
            List<Node> nodes = w.getNodes();
            
            /* Omit the last node if it is the same as the first */
            final boolean closed = nodes.get(0).equals(nodes.get(nodes.size() - 1));
            if (closed) {
                nodes.remove(nodes.size() - 1);
            }
            for (Node n: nodes) {
                boolean nodeBelongsToManyWays = nodeGluesWays(n); 
                boolean tagged = n.isTagged();
                if (!nodeBelongsToManyWays && !tagged) {
                    movableNodes.add(n);
                }
            }
        }
        if (movableNodes.isEmpty()) {
            alertNoMovableNodesFound();
            return;
        }
        Logging.debug("Working with {0} movable nodes", movableNodes.size());
        
        /* Determine areas and linear ways to which snapping is allowed */
        
        /* Be careful to which natural features snapping has to be done */
        final List<String> acceptedNatural = Arrays.asList("wood", "scrub", 
                "heath", "moor", "grassland", "fell", "water", "wetland", 
                "beach", "coastline");
        /* Things like nature reserves, political boundaries or e.g. military
           use should not be allowed for snapping */
        final List<String> ignoredLanduses = Arrays.asList("military");
        
        /* Do not include linear waterways here, they come later */
        final List<String> acceptedWaterways = Arrays.asList("riverbank", "dock");
        
        /* Snap to islands */
        final List<String> acceptedPlaces = Arrays.asList("islet", "island");
        
        final Collection<Way> allWays = getLayerManager().getEditDataSet().getWays();
        ArrayList<Way> candidateWays = new ArrayList<>();
        
        /* First pass is to select all ways to enclose areas */
        for (final Way w : allWays) {
            boolean isNatural = w.get("natural") != null
                                && acceptedNatural.contains(w.get("natural"));
            boolean isLanduse = (w.get("landuse") != null) 
                                 && !ignoredLanduses.contains(w.get("landuse")); 
            boolean isWaterway = w.get("waterway") != null
                                 && acceptedWaterways.contains(w.get("waterway"));
            
            boolean isPlace = w.get("place") != null
                    && acceptedPlaces.contains(w.get("place"));
                       
            boolean accepted = isNatural || isLanduse || isWaterway || isPlace;
            if (accepted) {
                candidateWays.add(w);
            }
        }
        /* The second pass is to select way used to represent linear objects.
           Because linear ways will be the last in the candidateWays list, 
           movable nodes will have a chance to snap to areas first */
        /* FIXME: It is possible to include the same ways twice under these two 
           passes. It should not cause any correctness problems at snapping 
           because every node can be moved at most once, and won't move if it 
           already lies on a way */
        /* Disable it as it screws up highways badly */
//        for (final Way w : allWays) {
//            boolean isHighway = w.get("highway") != null;
//            
//            boolean accepted = isHighway;
//            if (accepted) {
//                candidateWays.add(w);
//            }
//        }

        Logging.debug("Working with {0} candidate ways", candidateWays.size());
               
        /* List of node movement commands followed by way change commands */
        final Collection<Command> allCommands = new ArrayList<>();
        
        for (final Way cw : candidateWays) {
            Logging.debug("Looking for nodes to to snap to way id {0} ({1} nodes)", cw.getId(), cw.getNodesCount());
            
            /* Exclude nodes that are outside a bounding box where 
            snapping is at all possible. It requires a bit wider box to
            allow snapping of nodes lying just outside cw's limits  */
            BBox extendedBBox = new BBox(cw.getBBox());
            LatLon br = extendedBBox.getBottomRight();
            LatLon tl = extendedBBox.getTopLeft();
            
            LatLon mp = new LatLon(br.lat(), tl.lon()); /* The third point BBox */
            
            double widthDegrees = Math.abs(mp.lon() - br.lon());
            double heightDegrees = Math.abs(mp.lat() - tl.lat());
            double widthMeters = mp.greatCircleDistance(br);
            double heightMeters = mp.greatCircleDistance(tl); 
            
            
            double delta_lat = threshold * heightDegrees / heightMeters ;
            double delta_lon = threshold * widthDegrees / widthMeters; 
            
            LatLon nbr = new LatLon(br.lat() - delta_lat, br.lon() + delta_lon);
            LatLon ntl = new LatLon(tl.lat() + delta_lat, tl.lon() - delta_lon );
            
            
            extendedBBox.add(nbr);
            extendedBBox.add(ntl);

            
//            try {
//                Logging.debug("Sleeping for one second");
//                TimeUnit.SECONDS.sleep(1);
//            } catch (InterruptedException unused) {
//                ;
//            }
            
            boolean nodesAdded = false;
            List<Node>mutatedNodes = new ArrayList<>(cw.getNodes());
            
            List<Node> allRepositionedNodes = new ArrayList<>();
                      
            for (Node n: movableNodes) { /* Try to find a place to snap n to cw */
//                Logging.debug("Trying to snap {0}", n);
                  
                /* Do not snap node to ways it is already on */
                if (n.getParentWays().contains(cw)) { // TODO is this a cheap call?
//                    Logging.debug("Node is already on this way", n);
                    continue;
                }
                
                if (!extendedBBox.bounds(n.getCoor())) {
//                    Logging.debug("Out of bounds for current way", n);
                    continue;
                }
                int insertionPosition = -1;
                for (int k = 0; k < mutatedNodes.size()-1; k ++) {
                    Pair<LatLon, Double> res = calculateNearestPointOnSegment(n,
                            mutatedNodes.get(k),
                            mutatedNodes.get(k+1));
                    LatLon newCoords = res.a; 
                    double distance = res.b;
                    if (distance < threshold) {
                        /* Two things happen:
                         1. n is scheduled to be moved to newCoords,
                         2. n is scheduled to be inserted into cw at k+1 position */
                        Logging.debug("Node {0} moves to {1}", n.toString(), newCoords.toString());
                        totalMovedNodes ++;
                        allCommands.add(new MoveCommand(n, newCoords));
                        allRepositionedNodes.add(n);
                        insertionPosition = k+1;                    
                        break; // new home for n at cw has been found
                    }
                }
                if (insertionPosition >= 0) {
                    nodesAdded = true;
                    mutatedNodes.add(insertionPosition, n);
                }
            }
            /* All nodes that have been moved after current iteration should be
               excluded from movableNodes to prevent further attempts to snap 
               them somewhere */
            for (Node repNode: allRepositionedNodes) {
                movableNodes.remove(repNode);
            }
            if (nodesAdded) {
                /* Create a copy of this way, add updated list of nodes and 
                   register it in a change command */
                Way nw = new Way(cw);
                nw.setNodes(mutatedNodes);
                allCommands.add(new ChangeCommand(cw, nw));
            }
        }

        String infoMsg = tr("Snapped {0} nodes", totalMovedNodes);
        new Notification(infoMsg).setIcon(JOptionPane.INFORMATION_MESSAGE).show();
        Logging.debug(infoMsg);
        
        if (totalMovedNodes > 0) {
            final SequenceCommand rootCommand = new SequenceCommand(
                tr("Snap {0} nodes", totalMovedNodes), 
                allCommands);
            UndoRedoHandler.getInstance().add(rootCommand);
            MainApplication.getMap().repaint();
        }
    }
  
    /** Finds a point on line segment [b, c] that is closest to a.
     * @param a - the point
     * @param b, @param c - ends of the segment
     * @return pair of projection's coordinates and distance from @param a to it
     * XXX: the algorithm for finding a projection to a line works in assumption
     * for Cartesian coordinates and two-dimensional plane. It is not true for
     * (lat, lon) pairs and Earth surface. As a result,
     * the resulting point lies on a curve connecting b and c somewhat roughly
     * inside their bounding box. */
    private static Pair<LatLon, Double> calculateNearestPointOnSegment(
                                                        final Node a,
                                                        final Node b,
                                                        final Node c) {
        LatLon a_p = a.getCoor();
        LatLon b_p = b.getCoor();
        LatLon c_p = c.getCoor();

        /* An arbitrarily chosen threshold for squared length of [b;c]. For best
           results it should depend on chosen snapping threshold converted to 
           degrees */
        final double roundingThreshold = 1e-10;
        
        double px = c_p.lon() - b_p.lon();
        double py = c_p.lat() - b_p.lat();
        double squaredLength = px * px + py * py;
        double t = 0.0;
        if (Math.abs(squaredLength) > roundingThreshold ) { 
            t = ((a_p.lon() - b_p.lon()) * px + (a_p.lat() - b_p.lat()) * py) 
                    / squaredLength;
        }
        /* Bind t to the range (0.0; 1.0) */
        t = Math.max(t, 0.0);
        t = Math.min(t, 1.0); 

        double lon = b_p.lon() + t * px;
        double lat = b_p.lat() + t * py;
        LatLon proj = new LatLon(lat, lon);
        double dist = a_p.greatCircleDistance(proj);
        Pair<LatLon, Double> result = new Pair<>(proj, dist);

        //Logging.debug("measuring {0} to [{1}, {2}]: distance is {3}", a_p, b_p, c_p, result.b);
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

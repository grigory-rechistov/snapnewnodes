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
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.DeleteAction;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.HelpAwareOptionPane;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Geometry;
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

    public static Comparator<Way> WayComparator = new Comparator<Way>() {

        @Override
        public int compare(Way w1, Way w2) {
           Double w1l = w1.getLength();
           Double w2l = w2.getLength();

           return w2l.compareTo(w1l);
        }
    };

    @Override
    public void actionPerformed(final ActionEvent e) {
        Logging.debug("Snap action started");
        final double distThreshold = Config.getPref().getDouble(
                    SnapNewNodesPreferenceSetting.DIST_THRESHOLD, 10);
        long startTime = System.nanoTime();
        int totalMovedNodes = 0 ;

        final DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null)
            return;
        ds.beginUpdate();
        try {
            List<Way> selectedWays = ds.getSelectedWays().stream()
                    .filter(p -> !p.isIncomplete())
                    .collect(Collectors.toList());
            if (selectedWays.size() != 2) {
                new Notification(
                        tr("Please select exactly least two ways to snap."))
                        .setIcon(JOptionPane.INFORMATION_MESSAGE)
                        .setDuration(Notification.TIME_SHORT)
                        .show();
                return;
            }

            final Way srcWay = selectedWays.get(0);
            final Way dstWay = selectedWays.get(1);

            final int srcWaySize = srcWay.getNodesCount();
            final boolean srcWayIsClosed = srcWay.isClosed();

            Logging.debug("Snapping way {0} to way {1}",
                    srcWay.getDisplayName(DefaultNameFormatter.getInstance()),
                    dstWay.getDisplayName(DefaultNameFormatter.getInstance()));

            List<ReplacementPairs> replPairs = getReplacementPairs(distThreshold, srcWay, dstWay);

            if (replPairs.size() > 0) {
                /* add a fake stub end item to allow copying of the tail */
                ReplacementPairs terminatorEntry = new ReplacementPairs();
                terminatorEntry.srcStart = srcWaySize + 1; // outside of boundaries to never be reached
                replPairs.add(terminatorEntry);

                /* List of dataset modification commands to be formed */
                final Collection<Command> allCommands = new ArrayList<>();

                /* Collect new nodes of srcWay into a new list */
                List<Node>newSrcNodes = new ArrayList<>();

                int curPairIndex = 0;
                int i = 0;
                while (i < srcWaySize) {
                    ReplacementPairs curP = replPairs.get(curPairIndex);

                    /* There should be no way to enter deep into a replaced segment */
                    assert i <= curP.srcStart;

                    if (i == curP.srcStart) {
                        /* It has reached the replacement segment start.
                         * Copy new start and end nodes and all nodes
                         * in between now come from dstWay */
                        assert curP.srcN != null;
                        Node startProj = new Node(curP.srcN);
                        AddCommand spcmd = new AddCommand(ds, startProj);
                        allCommands.add(spcmd);
                        newSrcNodes.add(startProj);

                        /* Extract a segment from dstWay with correct order of nodes */
                        int dstStart = curP.dstStart;
                        int dstEnd = curP.dstEnd;

                        /* TODO direction should be chosen at tracking stage by looking at intermediate nodes */
                        int direction = curP.direction;
                        if (direction == 0) { // TODO unclear when it can happen
                            direction = dstStart > dstEnd ? -1 : 1;
                        }

                        Logging.debug(
                            tr("Copying dest nodes in slice {0}:{1} direction {2}",
                                    dstStart, dstEnd, direction));

                        /* Copy dst nodes with respect of possibility for wrap around */
                        int p = dstStart;
                        while (p != dstEnd) {
                            Node dstNode = dstWay.getNode(p);
                            newSrcNodes.add(dstNode);
                            assert direction != 0;
                            p = p + direction;
                            if (p < 0){ /* wrap around zero */
                                p = dstWay.getNodesCount()-1 ;
                            } else if (p >= dstWay.getNodesCount()) { /* warp around max node */
                                p = 0;
                            }
                        }

                        /* Add final projection node if it is different
                           from start projection node */
                        if (curP.srcStart != curP.srcEnd) { // TODO should projections' coordinates be checked instead?
                            assert curP.dstN != null;
                            Node endProj = new Node(curP.dstN);
                            AddCommand epcmd = new AddCommand(ds, endProj);
                            allCommands.add(epcmd);
                            newSrcNodes.add(endProj);
                        }
                        curPairIndex ++; // now track the next segment pair
                        i = curP.srcEnd; // skip all old nodes of the segment
                    } else { // preserve the original node
                        newSrcNodes.add(srcWay.getNode(i));
                    }
                    /* At this point either of two things has happened:
                     1. A source node[i] was added to newSrcNodes
                     2. projection srcN, zero or more dst nodes and projection
                        dstN (if it is different from srcN) were copied
                        to newSrcNodes
                     */
                    i ++;
                }

                fixSmallAngles(newSrcNodes);

                if (srcWayIsClosed) { /* Make sure to close the new way */
                    Node firstNode = newSrcNodes.get(0);
                    newSrcNodes.set(newSrcNodes.size()-1, firstNode);
                }

                allCommands.add(new ChangeNodesCommand(srcWay, newSrcNodes)); // TODO use ChangeCommand instead?

                /* Delete nodes that are no longer on new way.
                 * NOTE: for debugging purposes, it actually helps to comment
                 * this section out to be able to see where the original
                 * positions of nodes were as left-overs */
                List<Node> deletedNodes = new ArrayList<>();
                for (Node n: srcWay.getNodes()) {
                    if (!newSrcNodes.contains(n) && (n.getReferrers().size() <= 1)) {
                        /* The node is no longer on the way and there are no other
                         * ways to reference this node */
                        deletedNodes.add(n);
                    }
                }
                if (!deletedNodes.isEmpty()) {
                    DeleteCommand dc = new DeleteCommand(deletedNodes);
                    allCommands.add(dc);
                }

                final SequenceCommand rootCommand = new SequenceCommand(
                            tr("Snap {0} nodes", totalMovedNodes),
                                allCommands);
                UndoRedoHandler.getInstance().add(rootCommand);
                MainApplication.getMap().repaint();
            } else {
                new Notification(
                        tr("No nodes or segments to snap were found."))
                        .setIcon(JOptionPane.INFORMATION_MESSAGE)
                        .setDuration(Notification.TIME_SHORT)
                        .show();
            }

            String infoMsg = tr("Snapped {0} nodes", totalMovedNodes);
            new Notification(infoMsg).setIcon(JOptionPane.INFORMATION_MESSAGE).show();
            Logging.debug(infoMsg);

        } finally {
            ds.endUpdate();
        }

        long endTime = System.nanoTime();
        double durationSeconds = (endTime - startTime) / 1.0e9;
        Logging.debug("It took {0} seconds", durationSeconds);
    }

    /** Exclude nodes with same coordinates or having zero or small degrees
     * between adjacent segments
     * @param newSrcNodes - list of nodes to modify
     */
    private void fixSmallAngles(List<Node> newSrcNodes) {
        /* TODO make angleThreshold a configurable plugin parameter */
        final double angleThreshold = 0.5; // in degrees
        int totalSmallAngledNodes = 0;
        /* Find a node that has a small angle, delete it a repeat search
         * over the modified list until we cannot find such a node */
        while (newSrcNodes.size() > 3) {
            int chosenIndex = -1;

            // XXX loop below does not test angle at the first/last nodes
            for (int k=1; k < newSrcNodes.size()-1; k ++) {
                Node prev = newSrcNodes.get(k-1);
                Node middle = newSrcNodes.get(k);
                Node next = newSrcNodes.get(k+1);

                /* First check for duplicate coordinates */
                if (prev.getCoor().equals(middle.getCoor())) {
                    chosenIndex = k;
                    break;
                }

                double angle = Geometry.getNormalizedAngleInDegrees(
                                Geometry.getCornerAngle(
                                            prev.getEastNorth(),
                                            middle.getEastNorth(),
                                            next.getEastNorth()));
                if (angle < angleThreshold) {
                    chosenIndex = k;
                    break;
                }
            }

            if (chosenIndex == -1) { // no more nodes
                break;
            } else {
                newSrcNodes.remove(chosenIndex);
                totalSmallAngledNodes++;
            }
        }
        Logging.debug(tr("Excluded {0} nodes with small angles", totalSmallAngledNodes));
        /* TODO: some of the excluded nodes may be now orphaned.
         * They should be deleted if nothing else references them */
    }

    /**
     * @param distThreshold distance between nodes and ways to start snapping
     * @param srcWay - from which way to snap nodes
     * @param dstWay - to which way to snap
     * @return list of tuples that contain all segments of srcWay that need to
     * be replaced with segments of dstWay and new nodes to be created
     * at transition points */
    private List<ReplacementPairs> getReplacementPairs(final double distThreshold, final Way srcWay, final Way dstWay) {
        final int srcWaySize = srcWay.getNodesCount();
        List<ReplacementPairs> replPairs = new ArrayList<>();

        ReplacementPairs curPair = new ReplacementPairs();

        for (int i = 0; i < srcWaySize; i ++) { // TODO the last node is equal to the first! Exclude it?
            Node n = srcWay.getNode(i);
            SnappingPlace sp = null;

//                if (n.getParentWays().contains(dstWay)) {
//                    sp = new SnappingPlace(null,  Double.POSITIVE_INFINITY, -1); // mark the node as "unsnappable"
//                } else {
            sp = calculateNearestPointOnWay(n, dstWay);
            assert sp.dstIndex >= 0;
//                }

            if (curPair.srcStart < 0 && sp.distance <= distThreshold) {
                /* not tracking before, start tracking now */
                curPair.srcStart = i;
                curPair.srcN = sp.projectionCoord;
                curPair.dstStart = sp.dstIndex;

                /* Until we know for sure, mark it as an end point as well */
                curPair.srcEnd = curPair.srcStart;
                curPair.dstEnd = curPair.dstStart;
                curPair.dstN = curPair.srcN;
                curPair.direction = 0; // unknown yet

            } else if (curPair.srcStart >= 0 && (sp.distance > distThreshold))
            {   /* Was tracking, stop tracking, because next node is too far away */
                /* Record the source and replacement segments */
                assert i > 0; // cannot be for the very first node
                assert curPair.srcStart >=0;
                assert curPair.dstStart >=0;
                assert curPair.dstEnd >=0;
                assert curPair.srcEnd >=0;
                replPairs.add(new ReplacementPairs(curPair));
//                    totalMovedNodes += curPair.srcEnd - curPair.srcStart + 1;
                curPair.reset();
            } else if (curPair.srcStart >= 0 && sp.distance <= distThreshold) {
                /* Continue tracking, record the last known end point */
                // TODO record in which direction we started to circle
                int deltaDstIndex = sp.dstIndex - curPair.dstEnd;
                int newDirection = deltaDstIndex > 0 ? 1 : deltaDstIndex < 0? -1 :0;

                if (curPair.direction != 0 && curPair.direction != newDirection) {
                    /* This means that projection point jump to
                     * another branch of dstWay */
                    // TODO handled wrap around zero case: stop currently tracked
                    // segment
                }

                curPair.srcEnd = i;
                curPair.dstEnd = sp.dstIndex;
                curPair.dstN = sp.projectionCoord;
                curPair.direction = newDirection;


            } /* Otherwise continue tracking outside of snapping threshold */
        }

        if (curPair.srcStart >= 0 ) { /* we are still tracking, close it at the last node */
            replPairs.add(new ReplacementPairs(curPair));
//                totalMovedNodes += curPair.srcEnd - curPair.srcStart + 1;
        }
        return replPairs;
    }

    /** Finds a point on line segment [b, c] that is closest to a.
     * @param a - the point
     * @param b - first end of the segment
     * @param c - second end of the segment
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
        final double roundingThreshold = 1e-14;

        double px = c_p.lon() - b_p.lon();
        double py = c_p.lat() - b_p.lat();
        double squaredLength = px * px + py * py;
        double t = 0.0;
        if (Math.abs(squaredLength) > roundingThreshold ) {
            t = ((a_p.lon() - b_p.lon()) * px + (a_p.lat() - b_p.lat()) * py)
                    / squaredLength;
        }
        /* Bind t to the range [0.0; 1.0] */
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

    /** Find a closest point on a way to n
     * @param n - the node to find a projection of
     * @param w - way to snap to
     * @return {@link SnappingPlace} for that node
     */
    private static SnappingPlace calculateNearestPointOnWay(final Node n, final Way w) {
        int insPos = -1;
        double minDistance = Double.POSITIVE_INFINITY;
        LatLon newCoords = null;

        // TODO add a check for bounding box of w and n?

        for (int k = 0; k < w.getNodesCount()-1; k ++) {
            Pair<LatLon, Double> res = calculateNearestPointOnSegment(n,
                    w.getNode(k),
                    w.getNode(k+1));
            double distance = res.b;
            if (distance < minDistance) {
                minDistance = distance;
                insPos = k;
                newCoords = res.a;
            }
        }
        return new SnappingPlace(newCoords, minDistance, insPos);
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

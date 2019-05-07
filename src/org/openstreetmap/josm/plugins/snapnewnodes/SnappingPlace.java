package org.openstreetmap.josm.plugins.snapnewnodes;

import org.openstreetmap.josm.data.coor.LatLon;
//import org.openstreetmap.josm.data.osm.Node;

/** A tuple of values describing where two ways have intersected
 */
public class SnappingPlace {
    public int movedNode; // index of node in the source way
    public LatLon projectionCoord; // where the source node would snap to
    public double distance; // in meters between source node and its projection
    public int targetIndex;  // index of node on target way
    
    public SnappingPlace(int mv, LatLon pc, double d, int ti) {
        this.movedNode = mv;
        this.projectionCoord = pc;
        this.distance = d;
        this.targetIndex = ti;
    }
    
}

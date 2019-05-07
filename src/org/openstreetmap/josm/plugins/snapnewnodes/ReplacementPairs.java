package org.openstreetmap.josm.plugins.snapnewnodes;

import org.openstreetmap.josm.data.coor.LatLon;

public class ReplacementPairs {

    /** Indexes of nodes in source and destination ways */
    public int srcStart;
    public int srcEnd;
    public int dstStart;
    public int dstEnd;
    public LatLon srcN;
    public LatLon dstN;
    
    public ReplacementPairs() {
        this.reset();
    }

    public ReplacementPairs(ReplacementPairs p) {
        this.srcStart = p.srcStart;
        this.srcEnd = p.srcEnd;
        this.dstStart = p.dstStart;
        this.dstEnd = p.dstEnd;
        this.srcN = p.srcN;
        this.dstN = p.dstN;
    }

    public void reset() {
        this.srcStart = -1;
        this.srcEnd = -1;
        this.dstStart = -1;
        this.dstEnd = -1;
        this.srcN = null;
        this.dstN = null;
    }

}

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

import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;

public class SnapNewNodesPreferenceSetting extends DefaultTabPreferenceSetting {

    static final String DIST_THRESHOLD = "snap-new-nodes.dist.threshold";

    private final JTextField distanceThreshold = new JosmTextField(8);

    public SnapNewNodesPreferenceSetting() {
        super("snapnewnodes", tr("Snap New Nodes"),
                tr("A node of a way is merged to a node or segment of another way if distance between them is less than the specified threshold." +
                   " Ways to be snapped to are limited to roads (highway = *), land cover (landuse=*) and similar features." +
                   " Nodes already connecting two or more ways are never moved."
             ));
    }

    @Override
    public void addGui(final PreferenceTabbedPane gui) {
        final JPanel tab = gui.createPreferenceTab(this);

        distanceThreshold.setText(Config.getPref().get(DIST_THRESHOLD, "10"));
        tab.add(new JLabel(tr("Distance Threshold (in meters)")), GBC.std());
        tab.add(distanceThreshold, GBC.eol().fill(GBC.HORIZONTAL).insets(5,0,0,5));

        tab.add(Box.createVerticalGlue(), GBC.eol().fill(GBC.VERTICAL));
    }

    @Override
    public boolean ok() {
        Config.getPref().put(DIST_THRESHOLD, distanceThreshold.getText());
        return false;
    }
}

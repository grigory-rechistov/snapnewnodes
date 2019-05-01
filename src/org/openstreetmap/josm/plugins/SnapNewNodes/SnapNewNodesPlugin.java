// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.snapnewnodes;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class SnapNewNodesPlugin extends Plugin {

    public SnapNewNodesPlugin(final PluginInformation info) {
        super(info);
        MainMenu.add(MainApplication.getMenu().moreToolsMenu, new SnapNewNodesAction());
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new SnapNewNodesPreferenceSetting();
    }
}

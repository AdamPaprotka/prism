package dev.prismglass.module;

import dev.prismglass.Prism;
import dev.prismglass.setting.Setting;
import java.util.ArrayList;
import java.util.List;

/**
 * A background feature that isn't a module you toggle while playing: never in the ClickGUI panels, the HUD list,
 * search or panic. Switched on in the ClickGUI Config tab (Services), saved on its own in prism/services.json, so
 * switching or importing profiles, reloading the config and panicking leave it running.
 */
public abstract class Service extends Module {
    protected Service(String name, String description) {
        super(name, description, Category.CLIENT);
        drawn.set(false);
    }

    @Override
    public void setEnabled(boolean value) {
        boolean was = isEnabled();
        super.setEnabled(value);
        if (was != isEnabled() && Prism.config() != null) Prism.config().saveServices();
    }

    /** The settings shown under the service in the Config tab (no bind / drawn / notify). */
    public List<Setting<?>> serviceSettings() {
        List<Setting<?>> out = new ArrayList<>(getSettings());
        out.remove(bind);
        out.remove(bindMode);
        out.remove(drawn);
        out.remove(notify);
        return out;
    }
}

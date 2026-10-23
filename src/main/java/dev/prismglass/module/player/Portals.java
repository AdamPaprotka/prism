package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;

/**
 * Use chat, your inventory and containers while standing in a nether portal. Vanilla closes any screen that isn't
 * marked "allowed in portal" every tick you're inside one (LocalPlayer.handlePortalTransitionEffect); the server
 * doesn't care, so this is client-only. Changing dimension still closes containers server-side.
 */
public class Portals extends Module {
    public Portals() { super("Portals", "Use screens (chat, inventory, containers) inside nether portals.", Category.PLAYER); }
}

package dev.prismglass.module.render;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChamsState;
import dev.prismglass.util.ColorUtil;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.player.Player;

/**
 * When a player pops a totem (entity event 35), freezes their model right there as a glowing ghost that fades out
 * and drifts up. The ghost is the player's own render state captured at the pop, re-added to the level's entity list
 * every frame (PopChamsMixin) and drawn by ChamsMixin as a single tinted pass: no armour, items or name.
 */
public class PopChams extends Module {
    public final ColorSetting color = color("Color", 0xA0FFD86B, "Ghost colour (alpha = starting opacity).");
    public final NumberSetting duration = num("Duration", 1.5, 0.3, 5, 0.1, "Seconds until the ghost is gone.");
    public final NumberSetting rise = num("Rise", 0.6, 0, 3, 0.1, "Blocks the ghost floats up over its life.");
    public final NumberSetting max = num("Max", 6, 1, 20, 1, "Most ghosts at once.");
    public final BoolSetting self = bool("Self", false, "Your own pops too (third person).");
    public final BoolSetting throughWalls = bool("ThroughWalls", true, "See ghosts through blocks.");

    private record Ghost(LivingEntityRenderState state, long born, double baseY) {}

    private final ConcurrentLinkedQueue<ClientboundEntityEventPacket> pops = new ConcurrentLinkedQueue<>();
    private final List<Ghost> ghosts = new ArrayList<>();

    public PopChams() { super("PopChams", "A fading ghost of a player's model where they popped a totem.", Category.RENDER); }

    @Override public void onDisable() { ghosts.clear(); pops.clear(); }

    @Override
    public void onPacketReceive(PacketEvent event) {
        // netty thread: only queue it, the entity is looked up on the main thread
        if (event.packet instanceof ClientboundEntityEventPacket p && p.getEventId() == 35) pops.add(p);
    }

    @Override
    public void onTick() {
        ClientboundEntityEventPacket p;
        while ((p = pops.poll()) != null) {
            if (!(p.getEntity(mc.level) instanceof Player player) || (player == mc.player && !self.get())) continue;
            EntityRenderState s = mc.getEntityRenderDispatcher().extractEntity(player, 1.0f);
            if (!(s instanceof LivingEntityRenderState state)) continue;
            state.nameTag = null;
            state.hasRedOverlay = false;
            // the pop arrives with health at 0, before the totem's heal: don't freeze them mid death-fall
            state.deathTime = 0;
            state.outlineColor = 0;
            ((ChamsState) state).prism$setGhost(true);
            ghosts.add(new Ghost(state, System.nanoTime(), state.y));
            while (ghosts.size() > max.getInt()) ghosts.remove(0);
        }
    }

    /** PopChamsMixin: after vanilla collected this frame's entities, add the living ghosts. */
    public void addGhosts(List<EntityRenderState> out) {
        long now = System.nanoTime();
        float life = duration.getFloat();
        for (Iterator<Ghost> it = ghosts.iterator(); it.hasNext(); ) {
            Ghost g = it.next();
            float age = (now - g.born()) / 1e9f / life;
            if (age >= 1) { it.remove(); continue; }
            float fade = 1 - age * age; // stays bright, then fades out quickly
            g.state().y = g.baseY() + rise.get() * age;
            ((ChamsState) g.state()).prism$setChams(ColorUtil.fade(color.color(), fade));
            out.add(g.state());
        }
    }

    /** Dev checks: the newest ghost's state, or null. */
    public LivingEntityRenderState newestGhost() { return ghosts.isEmpty() ? null : ghosts.get(ghosts.size() - 1).state(); }

    /** Dev checks: ghosts alive now. */
    public int ghostCount() { return ghosts.size(); }

    @Override public String getInfo() { return ghosts.isEmpty() ? null : String.valueOf(ghosts.size()); }
}

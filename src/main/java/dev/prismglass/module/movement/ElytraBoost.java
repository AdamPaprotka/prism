package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.InvUtil;
import dev.prismglass.util.KeyUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra boosts on demand. Rocket fires a real firework from the hotbar (or offhand) and puts the slot back the next
 * tick. Fake gives the push a rocket would (velocity += look * 0.1 + (look * 1.5 - velocity) * 0.5 a tick) on your
 * client only: no rockets used, but the server never saw one. [Grim-unsafe] Boosts while BoostKey is held, or by
 * itself under MinSpeed.
 */
public class ElytraBoost extends Module {
    public final ModeSetting mode = mode("Mode", "Rocket", "Rocket = a real firework (safe). Fake = client-side push [Grim-unsafe].", "Rocket", "Fake");
    public final KeySetting boostKey = key("BoostKey", KeyUtil.NONE, "Hold to boost (None = Auto only).");
    public final BoolSetting auto = bool("Auto", true, "Boost by itself when you slow down.");
    public final NumberSetting minSpeed = num("MinSpeed", 18, 2, 60, 1, "Auto: boost below this speed (blocks/s).").visibleWhen(() -> auto.get());
    public final NumberSetting cooldown = num("Cooldown", 30, 5, 100, 1, "Ticks between boosts (a rocket lasts about 25-35).");
    public final NumberSetting fakeTicks = num("FakeTicks", 20, 5, 60, 1, "Fake: how long one boost pushes.").visibleWhen(() -> mode.is("Fake"));

    private int wait, restore = -1, fakeLeft;

    public ElytraBoost() { super("ElytraBoost", "Firework boosts on a key or when you slow down (Fake: no rockets).", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (restore != -1) { InvUtil.restore(restore); restore = -1; }
        if (wait > 0) wait--;
        var p = mc.player;
        if (!p.isFallFlying()) { fakeLeft = 0; return; }
        if (fakeLeft > 0) { fakeLeft--; push(); }
        double speed = p.getDeltaMovement().length() * 20;
        boolean want = (KeyUtil.isDown(boostKey.get()) && mc.screen == null) || (auto.get() && speed < minSpeed.get());
        if (!want || wait > 0) return;
        if (mode.is("Fake")) { fakeLeft = fakeTicks.getInt(); wait = Math.max(cooldown.getInt(), fakeTicks.getInt()); return; }
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) { mc.gameMode.useItem(p, InteractionHand.OFF_HAND); wait = cooldown.getInt(); return; }
        int slot = InvUtil.findHotbar(Items.FIREWORK_ROCKET);
        if (slot == -1) return;
        int prev = p.getInventory().getSelectedSlot();
        if (slot != prev) {
            if (!Prism.guard().canSwitchSlot()) return;
            InvUtil.swap(slot, false);
            restore = prev;
        }
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        wait = cooldown.getInt();
    }

    /** What a firework does to its rider each tick (FireworkRocketEntity.tick). */
    private void push() {
        Vec3 look = mc.player.getLookAngle(), v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(v.add(look.x * 0.1 + (look.x * 1.5 - v.x) * 0.5, look.y * 0.1 + (look.y * 1.5 - v.y) * 0.5, look.z * 0.1 + (look.z * 1.5 - v.z) * 0.5));
    }

    /** Dev checks: rockets or fake boosts started. */
    public boolean boosting() { return wait > 0; }
}

package dev.prismglass.dev;

import static dev.prismglass.dev.ModTest.*;
import static dev.prismglass.dev.ModCases.clearArea;

import dev.prismglass.Prism;
import dev.prismglass.module.combat.AutoMace;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.monster.zombie.Husk;

/** ModTest cases for AutoMace: real smash damage on a 1000 HP dummy, measured on the server. */
final class ModCases10 {
    private static final Minecraft mc = Minecraft.getInstance();

    /** Server copy of the dummy (kept by reference: the elytra loop flies well past serverEntity's 20 blocks). */
    private static Husk dummy;
    private static float lastHp, myLast, myLost;
    /** What the server thought of us last tick: the hit is judged on that (charge, sprint, ground, fall). */
    private static String serverSample = "";
    private static final List<Float> hits = new ArrayList<>();

    private ModCases10() {}

    /** A husk (doesn't burn at noon) with 1000 HP that never moves, {@code dz} blocks south of the start spot. */
    private static void summonDummy(int dz) {
        cmd(f("summon husk %.1f %d %.1f {NoAI:1b,PersistenceRequired:1b,Silent:1b,attributes:[{id:\"minecraft:max_health\",base:1000.0}],Health:1000.0f}",
            px + 0.5, py, pz + dz + 0.5));
        dummy = null;
        lastHp = 1000;
        serverSample = "";
        hits.clear();
        myLost = 0;
        myLast = -1;
    }

    /** Per tick: records every drop in the dummy's health as one hit, and the HP we lost ourselves. */
    private static void track() {
        if (dummy == null || dummy.isRemoved()) {
            dummy = null;
            for (var e : level().getAllEntities()) if (e instanceof Husk h && h.getMaxHealth() > 500) { dummy = h; break; }
            if (dummy == null) return;
            lastHp = dummy.getHealth();
        }
        float hp = dummy.getHealth();
        if (hp < lastHp - 0.5f) {
            hits.add(lastHp - hp);
            log(f("mace: hit %.1f (fall %.1f, y %.1f) server before: %s", lastHp - hp, Prism.modules().get(AutoMace.class).fall(), mc.player.getY(), serverSample));
        }
        lastHp = hp;
        var sp = splayer();
        serverSample = f("charge %.2f sprint %s ground %s fall %.1f gliding %s", sp.getAttackStrengthScale(0.5f), sp.isSprinting(), sp.onGround(), sp.fallDistance, sp.isFallFlying());
        float me = mc.player.getHealth();
        if (myLast >= 0 && me < myLast) myLost += myLast - me;
        myLast = me;
        if (me < 8) cmd("effect give @p instant_health 1 3");
    }

    private static String summary(String what) {
        double sum = 0, max = 0;
        for (float h : hits) { sum += h; max = Math.max(max, h); }
        return f("%s: %d smashes, avg %.1f, max %.1f, total %.1f, my hp lost %.1f, hits %s",
            what, hits.size(), hits.isEmpty() ? 0 : sum / hits.size(), max, sum, myLost, hits);
    }

    /** Server side: walk the dummy back and forth along x at walking speed (0.2 b/t, 6 blocks each way), like a strafing player. */
    private static void strafe() {
        Husk d = dummy;
        if (d == null) return;
        double x = px + 0.5 + 3 * Math.sin(caseTicks * 0.2 / 3);
        server().execute(() -> d.snapTo(x, d.getY(), d.getZ(), d.getYRot(), d.getXRot()));
    }

    private static void windJumpCase(String name, boolean moving) {
        add(name, 400, () -> {
            if (mc.player.isDeadOrDying()) mc.player.respawn();
            clearArea();
            cmd("kill @e[type=item]");
            cmd("effect clear @p");
            cmd("item replace entity @p hotbar.0 with diamond_sword");
            cmd("item replace entity @p hotbar.1 with mace");
            cmd("item replace entity @p hotbar.2 with wind_charge 64");
            mc.player.getInventory().setSelectedSlot(0);
            summonDummy(2);
            AutoMace m = enable(AutoMace.class);
            m.mobs.set(true);
            m.spam.set(false);
            m.windJump.set(true);
            m.autoHit.set(true);
        }, () -> {
            track();
            if (moving) strafe();
            if (caseTicks < 390) return false;
            log(summary(moving ? "automace windjump moving" : "automace windjump"));
            return !hits.isEmpty();
        }, () -> { disable(AutoMace.class); cmd("kill @e[type=husk]"); cmd("clear @p"); cmd("effect clear @p"); });
    }

    private static void spamCase(String name, boolean moving) {
        add(name, 1300, () -> {
            if (mc.player.isDeadOrDying()) mc.player.respawn();
            clearArea();
            cmd("kill @e[type=item]");
            cmd("effect clear @p");
            cmd("item replace entity @p armor.chest with elytra");
            cmd("item replace entity @p inventory.0 with diamond_chestplate");
            cmd("item replace entity @p hotbar.0 with mace");
            cmd("item replace entity @p hotbar.1 with diamond_sword");
            cmd("item replace entity @p weapon.offhand with firework_rocket 64");
            mc.player.getInventory().setSelectedSlot(0);
            summonDummy(6);
            AutoMace m = enable(AutoMace.class);
            m.mobs.set(true);
            m.spam.set(true);
            m.autoTarget.set(false);
            m.height.set(24.0);
        }, () -> {
            track();
            if (moving) strafe();
            AutoMace m = Prism.modules().get(AutoMace.class);
            if (caseTicks == 5) {
                for (var e : mc.level.entitiesForRendering()) if (e instanceof Husk h) { m.select(h); break; }
            }
            if (m.state().equals("DROP") && caseTicks % 2 == 0) log("spam drop: " + m.debugDrop);
            if (caseTicks % 10 == 0) log(f("spam: t=%d state=%s y=%.1f vy=%.2f gliding=%s chest=%s held=%s fall=%.1f",
                caseTicks, m.state(), mc.player.getY(), mc.player.getDeltaMovement().y, mc.player.isFallFlying(),
                mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem(), mc.player.getMainHandItem().getItem(), m.fall()));
            if (caseTicks < 1290) return false;
            log(summary(moving ? "automace spam moving" : "automace spam"));
            double avg = hits.stream().mapToDouble(Float::doubleValue).average().orElse(0);
            return hits.size() >= 2 && avg >= 30 && mc.player.isAlive();
        }, () -> {
            // down to the ground first (a teleport resets the fall): a case can time out mid-drop
            cmd(f("tp @p %.1f %d %.1f", px + 0.5, py, pz + 0.5));
            AutoMace m = Prism.modules().get(AutoMace.class);
            m.spam.set(false);
            disable(AutoMace.class);
            cmd("kill @e[type=husk]"); cmd("clear @p"); cmd("effect clear @p");
            cmd(f("tp @p %.1f %d %.1f", px + 0.5, py, pz + 0.5));
        });
    }


    private static int offAt;

    /** Switching Spam off mid-drop must put the elytra back on instead of letting us fall 20+ blocks. */
    private static void safeExitCase() {
        add("AutoMace Spam off mid-drop: elytra back on, no fall damage", 700, () -> {
            if (mc.player.isDeadOrDying()) mc.player.respawn();
            clearArea();
            cmd("kill @e[type=item]");
            cmd("effect clear @p");
            cmd("item replace entity @p armor.chest with elytra");
            cmd("item replace entity @p inventory.0 with diamond_chestplate");
            cmd("item replace entity @p hotbar.0 with mace");
            cmd("item replace entity @p hotbar.1 with diamond_sword");
            cmd("item replace entity @p weapon.offhand with firework_rocket 64");
            mc.player.getInventory().setSelectedSlot(0);
            summonDummy(6);
            AutoMace m = enable(AutoMace.class);
            m.mobs.set(true);
            m.spam.set(true);
            m.autoTarget.set(true);
            m.height.set(24.0);
            offAt = -1;
        }, () -> {
            track();
            AutoMace m = Prism.modules().get(AutoMace.class);
            if (offAt < 0 && m.state().equals("DROP") && m.fall() > 6) {
                offAt = caseTicks;
                m.autoHit.set(false); // no smash to save us: only the elytra may
                m.spam.set(false);
                log(f("safeexit: spam off at y %.1f fall %.1f", mc.player.getY(), m.fall()));
            }
            if (offAt >= 0 && caseTicks < offAt + 30) log(f("safeexit: t+%d y %.1f vy %.2f chest %s gliding %s canClick %s glidePending %s hp %.1f",
                caseTicks - offAt, mc.player.getY(), mc.player.getDeltaMovement().y, mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem(),
                mc.player.isFallFlying(), dev.prismglass.util.InvUtil.canClick(), dev.prismglass.manager.MovementHooks.glidePending(), mc.player.getHealth()));
            if (offAt < 0 || caseTicks < offAt + 80) return false;
            var chest = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem();
            log(f("safeexit: after 80 ticks alive=%s hp lost %.1f chest=%s ground=%s", mc.player.isAlive(), myLost, chest, mc.player.onGround()));
            return mc.player.isAlive() && myLost < 4 && chest == net.minecraft.world.item.Items.ELYTRA;
        }, () -> {
            cmd(f("tp @p %.1f %d %.1f", px + 0.5, py, pz + 0.5));
            Prism.modules().get(AutoMace.class).autoHit.set(true);
            disable(AutoMace.class);
            cmd("kill @e[type=husk]"); cmd("clear @p"); cmd("effect clear @p");
        });
    }


    /** Right-click picking: the long-range crosshair ray finds a dummy 20 blocks away (the click itself is a real mouse button). */
    private static void pickCase() {
        add("AutoMace pick: crosshair ray finds a target 20 blocks away", 60, () -> {
            if (mc.player.isDeadOrDying()) mc.player.respawn();
            clearArea();
            summonDummy(20);
            AutoMace m = enable(AutoMace.class);
            m.mobs.set(true);
            m.spam.set(false);
        }, () -> {
            Husk h = null;
            for (var e : mc.level.entitiesForRendering()) if (e instanceof Husk x) h = x;
            if (h == null || caseTicks < 5) return false;
            var aim = dev.prismglass.util.RotationUtil.toward(h.getBoundingBox().getCenter());
            mc.player.setYRot(aim[0]);
            mc.player.setXRot(aim[1]);
            AutoMace m = Prism.modules().get(AutoMace.class);
            var got = m.lookedAt(128);
            mc.player.setYRot(aim[0] + 40); // looking away: nothing
            var none = m.lookedAt(128);
            log(f("pick: facing -> %s, away -> %s", got == null ? "null" : got.getName().getString(), none == null ? "null" : none.getName().getString()));
            return got == h && none == null;
        }, () -> { disable(AutoMace.class); cmd("kill @e[type=husk]"); });
    }

    static void register() {
        pickCase();
        safeExitCase();
        windJumpCase("AutoMace WindJump moving: dummy strafes at walking speed", true);
        add("AutoMace WindJump: wind charge launch + smash on a 1000 HP dummy", 400, () -> {
            if (mc.player.isDeadOrDying()) mc.player.respawn();
            clearArea();
            cmd("kill @e[type=item]");
            cmd("effect clear @p");
            cmd("item replace entity @p hotbar.0 with diamond_sword");
            cmd("item replace entity @p hotbar.1 with mace");
            cmd("item replace entity @p hotbar.2 with wind_charge 64");
            mc.player.getInventory().setSelectedSlot(0);
            summonDummy(2);
            AutoMace m = enable(AutoMace.class);
            m.mobs.set(true);
            m.spam.set(false);
            m.windJump.set(true);
            m.autoHit.set(true);
        }, () -> {
            track();
            if (caseTicks % 20 == 0) log(f("mace: t=%d dummy=%s hp=%.0f me y=%.2f ground=%s fall=%.2f slot=%d held=%s dist=%.2f",
                caseTicks, dummy != null, dummy == null ? -1f : dummy.getHealth(), mc.player.getY(), mc.player.onGround(),
                Prism.modules().get(AutoMace.class).fall(), mc.player.getInventory().getSelectedSlot(),
                mc.player.getMainHandItem().getItem(), dummy == null ? -1 : Math.sqrt(dummy.distanceToSqr(mc.player))));
            if (caseTicks < 390) return false;
            log(summary("automace windjump"));
            return !hits.isEmpty();
        }, () -> { disable(AutoMace.class); cmd("kill @e[type=husk]"); cmd("clear @p"); cmd("effect clear @p"); });
        spamCase("AutoMace Spam: elytra loop on a picked dummy", false);
        spamCase("AutoMace Spam moving: elytra loop on a strafing dummy", true);
    }
}

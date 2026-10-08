package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.Module;
import dev.prismglass.module.movement.FastClimb;
import dev.prismglass.module.movement.NoJumpDelay;
import dev.prismglass.module.movement.NoSlow;
import dev.prismglass.module.movement.ReverseStep;
import dev.prismglass.module.movement.Scaffold;
import dev.prismglass.module.movement.Step;
import dev.prismglass.module.player.AirPlace;
import dev.prismglass.module.world.Timer;
import dev.prismglass.util.InvUtil;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The Grim scenarios. Controls first: legit play must be clean, an obvious cheat must flag. */
final class GrimCases {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final String[] NONE = {};

    private GrimCases() {}

    private static <T extends Module> T on(Class<T> c) {
        T m = Prism.modules().get(c);
        m.setEnabled(true);
        return m;
    }

    private static KeyMapping up() { return mc.options.keyUp; }
    private static KeyMapping jump() { return mc.options.keyJump; }
    private static KeyMapping sprint() { return mc.options.keySprint; }
    private static KeyMapping use() { return mc.options.keyUse; }

    private static void add(List<GrimTest.Case> cases, String name, int length, String[] prep, Runnable setup, IntConsumer body) {
        cases.add(new GrimTest.Case(name, length, prep, setup, body, () -> {}));
    }

    static void register(List<GrimTest.Case> cases) {
        // ---- controls ----
        add(cases, "legit", 100, NONE, () -> {}, t -> {
            GrimTest.hold(up(), true);
            GrimTest.hold(sprint(), t > 10);
            GrimTest.hold(jump(), t % 30 < 3);
        });
        add(cases, "speedcheat", 60, NONE, () -> {}, t -> {
            GrimTest.hold(up(), true);
            Vec3 v = mc.player.getDeltaMovement();
            if (mc.player.onGround()) mc.player.setDeltaMovement(v.x * 1.6, v.y, v.z * 1.6);
        });

        // ---- NoSlow: walk forward eating golden apples ----
        String[] apples = {"item replace entity @s hotbar.0 with golden_apple 64"};
        IntConsumer eat = t -> {
            GrimTest.hold(up(), true);
            GrimTest.hold(use(), true);
        };
        add(cases, "eat-vanilla", 100, apples, () -> InvUtil.swap(0, false), eat);
        add(cases, "noslow-vanilla", 100, apples, () -> {
            InvUtil.swap(0, false);
            on(NoSlow.class).mode.parse("Vanilla");
        }, eat);
        add(cases, "noslow-grim", 100, apples, () -> {
            InvUtil.swap(0, false);
            on(NoSlow.class).mode.parse("Grim");
        }, eat);

        // ---- Timer ----
        add(cases, "noslow-ncp", 100, apples, () -> {
            InvUtil.swap(0, false);
            on(NoSlow.class).mode.parse("NCP");
        }, eat);
        add(cases, "timer-108", 200, NONE, () -> {
            Timer tm = on(Timer.class);
            tm.mode.parse("Vanilla");
            tm.speed.set(1.08);
        }, t -> GrimTest.hold(up(), true));
        add(cases, "timer-ncp", 300, NONE, () -> {
            Timer tm = on(Timer.class);
            tm.mode.parse("NCP");
            tm.speed.set(2.0); // capped to the NCP-safe rate by the mode
        }, t -> GrimTest.hold(up(), true));
        add(cases, "speed-strafe", 100, NONE, () -> on(dev.prismglass.module.movement.Speed.class).mode.parse("Strafe"), t -> {
            GrimTest.hold(up(), true);
            GrimTest.hold(sprint(), true);
        });
        add(cases, "timer-vanilla", 60, NONE, () -> {
            Timer tm = on(Timer.class);
            tm.mode.parse("Vanilla");
            tm.speed.set(2.0);
        }, t -> GrimTest.hold(up(), true));
        add(cases, "timer-balance", 240, NONE, () -> {
            Timer tm = on(Timer.class);
            tm.mode.parse("Balance");
            tm.speed.set(2.0);
            tm.charge.set(0.6);
        }, t -> {
            GrimTest.hold(up(), (t / 40) % 2 == 0); // walk 40 ticks, stand 40 (recharge)
            if (t % 10 == 0) GrimTest.log(String.format("timer t=%d speed %.2f bank %.0fms", t, Prism.modules().get(Timer.class).speed(),
                Prism.modules().get(Timer.class).bank()));
        });

        // ---- jump / step ----
        String[] ceiling = {"fill -2 -58 -2 2 -58 60 stone"};
        IntConsumer headHit = t -> {
            GrimTest.hold(up(), true);
            GrimTest.hold(sprint(), true);
            GrimTest.hold(jump(), true);
        };
        add(cases, "headhit-vanilla", 100, ceiling, () -> {}, headHit);
        add(cases, "nojumpdelay", 100, ceiling, () -> on(NoJumpDelay.class), headHit);
        add(cases, "step", 60, new String[]{"fill -3 -60 4 3 -60 4 stone", "fill -3 -59 9 3 -59 9 stone", "fill -3 -60 9 3 -60 9 stone"},
            () -> { Step st = on(Step.class); st.height.set(1.0); st.mode.parse("Vanilla"); }, t -> GrimTest.hold(up(), true));
        add(cases, "step-ncp", 60, new String[]{"fill -3 -60 4 3 -60 4 stone", "fill -3 -59 9 3 -59 9 stone", "fill -3 -60 9 3 -60 9 stone"},
            () -> { Step st = on(Step.class); st.height.set(1.0); st.mode.parse("NCP"); }, t -> GrimTest.hold(up(), true));
        add(cases, "reversestep", 60, new String[]{"fill -2 -60 -2 2 -59 3 stone", "tp @s 0 -58 0 0 0"},
            () -> on(ReverseStep.class), t -> GrimTest.hold(up(), true));
        add(cases, "fastclimb", 80, new String[]{"fill -1 -60 2 1 -50 2 stone", "fill 0 -60 1 0 -51 1 ladder[facing=north]"},
            () -> on(FastClimb.class), t -> GrimTest.hold(up(), true));

        // ---- Scaffold / AirPlace ----
        String[] pillar = {"fill -1 -51 -1 1 -51 1 stone", "tp @s 0 -50 0 0 0", "item replace entity @s hotbar.1 with stone 64"};
        add(cases, "scaffold", 100, pillar, () -> on(Scaffold.class).tower.set(false), t -> {
            GrimTest.hold(up(), t > 5);
            Scaffold sc = Prism.modules().get(Scaffold.class);
            if (mc.player.getZ() > 3.5 && mc.player.getY() > -51) GrimTest.log(String.format(
                "info tick t=%d z %.3f ground %s shift %s wants %s rot %s yaw %.1f result %s below %s", t, mc.player.getZ(), mc.player.onGround(),
                mc.player.isShiftKeyDown(), sc.wantsSneak(), Prism.rotations().isActive(), Prism.rotations().getServerYaw(), sc.lastResult,
                mc.level.getBlockState(BlockPos.containing(mc.player.getX(), -51, mc.player.getZ())).getBlock().getName().getString()));
            if (false) GrimTest.log(String.format(
                "info fall t=%d z %.2f sneak %s wants %s rotActive %s serverYaw %.1f pitch %.1f below %s", t, mc.player.getZ(), mc.player.isShiftKeyDown(),
                Prism.modules().get(Scaffold.class).wantsSneak(), Prism.rotations().isActive(), Prism.rotations().getServerYaw(), Prism.rotations().getServerPitch(),
                mc.level.getBlockState(BlockPos.containing(mc.player.getX(), -51, mc.player.getZ())).getBlock()));
            if (t % 10 == 0) GrimTest.log(String.format("info scaffold t=%d y %.2f z %.2f ground %s teleports %d", t, mc.player.getY(), mc.player.getZ(),
                mc.player.onGround(), Prism.serverTeleports));
        });
        add(cases, "scaffold-tower", 100, pillar, () -> on(Scaffold.class).tower.set(true), t -> {
            GrimTest.hold(jump(), t > 5);
            if (t % 10 == 0) GrimTest.log(String.format("info tower t=%d y %.2f ground %s teleports %d", t, mc.player.getY(), mc.player.onGround(), Prism.serverTeleports));
        });
        add(cases, "scaffold-sneak", 100, pillar, () -> on(Scaffold.class).tower.set(false), t -> {
            GrimTest.hold(up(), t > 5);
            GrimTest.hold(mc.options.keyShift, true);
        });
        add(cases, "legit-bridge", 120, new String[]{"fill -1 -51 -1 1 -51 1 stone", "tp @s 0 -50 0 180 78", "item replace entity @s hotbar.0 with stone 64"},
            () -> InvUtil.swap(0, false), t -> {
            // plain vanilla sneak-bridging: back to the gap, look down at the edge, walk backwards, hold right click
            mc.player.setYRot(180);
            mc.player.setXRot(78);
            GrimTest.hold(mc.options.keyShift, true);
            GrimTest.hold(mc.options.keyDown, t > 5);
            GrimTest.hold(use(), t > 5);
            if (t % 20 == 0) GrimTest.log(String.format("info bridge t=%d y %.2f z %.2f", t, mc.player.getY(), mc.player.getZ()));
        });
        add(cases, "silentsneak", 100, NONE, () -> {}, t -> {
            GrimTest.hold(up(), true);
            GrimTest.hold(mc.options.keyShift, true);
            Prism.rotations().request((float) (90 + 50 * Math.sin(t * 0.7)), 80, 50);
        });
        add(cases, "silentsneak-edge", 80, pillar, () -> {}, t -> {
            GrimTest.hold(up(), t > 5);
            GrimTest.hold(mc.options.keyShift, true);
            Prism.rotations().request((float) (90 + 50 * Math.sin(t * 0.7)), 80, 50);
        });
        add(cases, "silentwalk-debug", 40, new String[]{"!ncp debug player {name} yes:moving"}, () -> {}, t -> {
            GrimTest.hold(up(), true);
            Prism.rotations().request((float) (90 + 50 * Math.sin(t * 0.7)), 80, 50);
            if (t % 2 == 0) GrimTest.log(String.format("info sw t=%d sentYaw %.1f keys %s sprint %s", t, Prism.rotations().getServerYaw(), mc.player.input.keyPresses, mc.player.isSprinting()));
        });
        add(cases, "silentwalk", 100, NONE, () -> {}, t -> {
            GrimTest.hold(up(), true);
            // wander the silent yaw like scaffold does, no sneak, no placing
            Prism.rotations().request((float) (90 + 50 * Math.sin(t * 0.7)), 80, 50);
        });
        add(cases, "sneak-edge", 80, pillar, () -> {}, t -> {
            GrimTest.hold(up(), t > 5);
            GrimTest.hold(mc.options.keyShift, true);
            if (t % 10 == 0) GrimTest.log(String.format("info edge t=%d y %.2f z %.3f", t, mc.player.getY(), mc.player.getZ()));
        });
        String[] stone = {"item replace entity @s hotbar.0 with stone 64"};
        String[] supported = {"item replace entity @s hotbar.0 with stone 64", "setblock 0 -58 4 stone"};
        // ---- elytra: start high, glide, climb, dive, hold ----
        String[] sky = {"tp @s 0 100 0 0 0", "item replace entity @s armor.chest with elytra", "item replace entity @s hotbar.1 with firework_rocket 64"};
        IntConsumer elytraLog = t -> {
            if (t % 20 == 0) GrimTest.log(String.format("info ely t=%d y %.1f speed %.2f gliding %s pitch %.1f", t, mc.player.getY(),
                mc.player.getDeltaMovement().length(), mc.player.isFallFlying(), Prism.rotations().getServerPitch()));
        };
        add(cases, "elytra-vanilla", 160, sky, () -> {}, t -> {
            // plain elytra: open it when falling (jump release -> press), look straight ahead
            mc.player.setXRot(5);
            GrimTest.hold(jump(), t > 6 && t % 2 == 0 && !mc.player.isFallFlying());
            elytraLog.accept(t);
        });
        add(cases, "elytra-grim", 200, sky, () -> on(dev.prismglass.module.movement.ElytraFly.class).mode.parse("Grim"), t -> {
            mc.player.setXRot(50); // camera looks down: the module flies on its own pitch
            GrimTest.hold(jump(), t > 70 && t <= 100);       // climb
            GrimTest.hold(mc.options.keyShift, t > 120 && t <= 140); // dive
            elytraLog.accept(t);
        });
        // ---- HighwayBuilder: wall to dig through, floor hole to pave, water to block off ----
        String[] course = {"fill -3 -60 4 3 -58 6 stone", "fill -1 -63 9 1 -61 11 air", "setblock 0 -60 14 water",
            "item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]", "item replace entity @s hotbar.1 with obsidian 64"};
        add(cases, "highway", 400, course, () -> {
            var hb = Prism.modules().get(dev.prismglass.module.world.HighwayBuilder.class);
            hb.width.set(3.0); hb.height.set(3.0); hb.floor.parse("Obsidian"); hb.rails.set(true); hb.walk.set(true);
            hb.setEnabled(true);
        }, t -> {
            if (t % 40 == 0) GrimTest.log(String.format("info hw t=%d z %.2f x %.2f y %.2f %s", t, mc.player.getZ(), mc.player.getX(), mc.player.getY(),
                Prism.modules().get(dev.prismglass.module.world.HighwayBuilder.class).getInfo()));
        });
        // ---- combat: a NoAI zombie that can't be knocked away ----
        String zombie = "summon zombie 0 -60 3.5 {NoAI:1b,PersistenceRequired:1b,Health:1000f,attributes:[{id:\"minecraft:max_health\",base:1000},{id:\"minecraft:knockback_resistance\",base:1}]}";
        String[] arena = {zombie, "item replace entity @s hotbar.0 with diamond_sword"};
        Runnable aura = () -> {
            InvUtil.swap(0, false);
            var ka = on(dev.prismglass.module.combat.KillAura.class);
            ka.players.set(false);
            ka.hostiles.set(true);
        };
        int[] attacks0 = {0};
        IntConsumer hits = t -> {
            if (t == 1) attacks0[0] = dev.prismglass.util.CombatUtil.attacksSent;
            if (t == 99) GrimTest.log("info attacks sent " + (dev.prismglass.util.CombatUtil.attacksSent - attacks0[0]));
            if (t == 99) mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, mc.player.getBoundingBox().inflate(8))
                .forEach(z -> GrimTest.log(String.format("info zombie hp %.0f", z.getHealth())));
        };
        add(cases, "killaura", 100, arena, aura, hits);
        add(cases, "crit-legit", 100, arena, () -> { aura.run(); on(dev.prismglass.module.combat.Criticals.class).mode.parse("Legit"); },
            t -> { GrimTest.hold(jump(), true); hits.accept(t); });
        add(cases, "crit-ncp", 100, arena, () -> { aura.run(); on(dev.prismglass.module.combat.Criticals.class).mode.parse("NCP"); }, hits);
        add(cases, "reach-ncp", 100, new String[]{zombie.replace("0 -60 3.5", "0 -60 4.6"), "item replace entity @s hotbar.0 with diamond_sword"}, () -> {
            aura.run();
            Prism.modules().get(dev.prismglass.module.combat.KillAura.class).range.set(4.2);
            on(dev.prismglass.module.player.Reach.class).mode.parse("NCP");
        }, hits);
        // ---- new combat modules + velocity + totems (zombie targets need difficulty easy) ----
        // full diamond = 20 armour; as an attribute so the command stays under the 256-character chat limit
        String armored = zombie.replace("{id:\"minecraft:knockback_resistance\",base:1}", "{id:\"minecraft:knockback_resistance\",base:1},{id:\"armor\",base:20}");
        add(cases, "killaura-armored", 100, new String[]{armored, "item replace entity @s hotbar.0 with diamond_sword"}, aura, hits);
        add(cases, "breachswap", 100, new String[]{armored, "item replace entity @s hotbar.0 with diamond_sword",
            "item replace entity @s hotbar.1 with mace[enchantments={breach:4}]"}, () -> { aura.run(); on(dev.prismglass.module.combat.BreachSwap.class); }, hits);
        add(cases, "automace", 160, new String[]{zombie, "item replace entity @s hotbar.0 with diamond_sword", "item replace entity @s hotbar.1 with mace",
            "item replace entity @s hotbar.2 with wind_charge 64"}, () -> {
            InvUtil.swap(0, false);
            var am = on(dev.prismglass.module.combat.AutoMace.class);
            am.mobs.set(true);
        }, t -> {
            if (t % 10 == 0) GrimTest.log(String.format("info mace t=%d y %.2f fall %.2f", t, mc.player.getY(), Prism.modules().get(dev.prismglass.module.combat.AutoMace.class).fall()));
            if (t == 159) mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, mc.player.getBoundingBox().inflate(8))
                .forEach(z -> GrimTest.log(String.format("info zombie hp %.0f", z.getHealth())));
        });
        add(cases, "spearkill", 120, new String[]{zombie.replace("0 -60 3.5", "0 -60 4"), "item replace entity @s hotbar.0 with iron_spear"}, () -> {
            InvUtil.swap(0, false);
            on(dev.prismglass.module.combat.SpearKill.class).mobs.set(true);
        }, t -> { if (t == 119) mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, mc.player.getBoundingBox().inflate(8))
            .forEach(z -> GrimTest.log(String.format("info zombie hp %.0f", z.getHealth()))); });
        IntConsumer hurt = t -> { if (t % 30 == 5) GrimTest.cmd("damage @s 1 minecraft:mob_attack by @e[type=zombie,limit=1,sort=nearest]"); };
        add(cases, "velocity-none", 100, new String[]{zombie.replace("0 -60 3.5", "0 -60 2")}, () -> {}, hurt);
        add(cases, "velocity-jumpreset", 100, new String[]{zombie.replace("0 -60 3.5", "0 -60 2")},
            () -> on(dev.prismglass.module.movement.Velocity.class).mode.parse("JumpReset"), hurt);
        add(cases, "velocity-budget", 100, new String[]{zombie.replace("0 -60 3.5", "0 -60 2")},
            () -> on(dev.prismglass.module.movement.Velocity.class).mode.parse("GrimBudget"), hurt);
        add(cases, "autototem", 100, new String[]{"give @s totem_of_undying 3"}, () -> on(dev.prismglass.module.combat.AutoTotem.class), t -> {
            if (t == 40) GrimTest.cmd("damage @s 40 minecraft:generic");
            if (t == 99) GrimTest.log("info totem offhand=" + mc.player.getOffhandItem().getItem() + " alive=" + mc.player.isAlive());
        });
        // ---- Bounce ElytraFly pitch sweep ----
        for (int pitch : new int[]{60, 70, 75, 80, 85, 90}) {
            add(cases, "bounce-" + pitch, 200, new String[]{"item replace entity @s armor.chest with elytra"}, () -> {
                var ef = on(dev.prismglass.module.movement.ElytraFly.class);
                ef.mode.parse("Bounce");
                ef.bouncePitch.set((double) pitch);
            }, t -> GrimTest.hold(up(), true));
        }

        add(cases, "airplace-vanilla", 40, stone, () -> {
            InvUtil.swap(0, false);
            on(AirPlace.class).mode.parse("Vanilla");
            mc.player.setXRot(-20); // a floating spot ahead and above
        }, t -> GrimTest.hold(use(), t >= 10 && t < 12));
        add(cases, "airplace-grim", 40, supported, () -> {
            InvUtil.swap(0, false);
            on(AirPlace.class).mode.parse("Grim");
        }, t -> {
            if (t == 10) Prism.modules().get(AirPlace.class).debugPlace(new BlockPos(0, -58, 3)); // in front of the support, eye level
            if (t == 35) GrimTest.log("airplace-grim placed=" + mc.level.getBlockState(new BlockPos(0, -58, 3)).is(Blocks.STONE));
        });
    }
}

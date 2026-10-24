package dev.prismglass.dev;

import static dev.prismglass.dev.ModTest.*;

import dev.prismglass.module.anarchy.Spam;
import dev.prismglass.module.combat.SwordBlock;
import dev.prismglass.module.player.*;
import dev.prismglass.module.render.*;
import dev.prismglass.module.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The ModTest cases, one block per module (see ModTest). */
final class ModCases {
    private static final Minecraft mc = Minecraft.getInstance();

    private ModCases() {}

    static void register() {
        registerBatch12();
        registerBatch3();
        registerBatch4();
        registerBatch5();
        registerBatch6();
        registerBatch7();
        registerNoFall();
        ModCases8.register();
    }

    static void registerBatch12() {
        // ---- player ------------------------------------------------------------------------------------------
        add("AutoGap eats a gapple when low", 140, () -> {
            cmd("give @p golden_apple 3");
            cmd("damage @p 13");
            enable(AutoGap.class);
        }, () -> splayer().getAbsorptionAmount() > 0, () -> {
            disable(AutoGap.class);
            cmd("effect clear @p");
            cmd("effect give @p instant_health 1 5");
        });

        add("Rotation locks yaw (client and server)", 60, () -> {
            Rotation r = enable(Rotation.class);
            r.yawMode.parse("Lock");
            r.yawAngle.parse("90");
        }, () -> Math.abs(Mth.wrapDegrees(mc.player.getYRot() - 90)) < 1 && Math.abs(Mth.wrapDegrees(splayer().getYRot() - 90)) < 1,
            () -> { dev.prismglass.Prism.modules().get(Rotation.class).yawMode.parse("Snap"); disable(Rotation.class); });

        add("PotionSpoof hides visuals, keeps effects, adds night vision", 60, () -> {
            cmd("effect give @p nausea 60 0");
            cmd("effect give @p blindness 60 0");
            PotionSpoof ps = enable(PotionSpoof.class);
            ps.nightVision.set(true);
        }, () -> mc.player.hasEffect(MobEffects.BLINDNESS) && mc.player.hasEffect(MobEffects.NAUSEA)
            && mc.player.getEffectBlendFactor(MobEffects.NAUSEA, 1) == 0
            && !new BlindnessFogEnvironment().isApplicable(null, mc.player)
            && mc.player.hasEffect(MobEffects.NIGHT_VISION) && !splayer().hasEffect(MobEffects.NIGHT_VISION), () -> {
            dev.prismglass.Prism.modules().get(PotionSpoof.class).nightVision.set(false);
            disable(PotionSpoof.class);
            if (mc.player.hasEffect(MobEffects.NIGHT_VISION)) log("FAIL PotionSpoof left its night vision on");
            cmd("effect clear @p");
        });

        add("Portals keeps the inventory open in a portal", 60, () -> {
            buildPortal();
            enable(Portals.class);
        }, () -> {
            if (caseTicks == 8) mc.gui.setScreen(new InventoryScreen(mc.player));
            return caseTicks >= 50 && mc.gui.screen() instanceof InventoryScreen && mc.player.portalEffectIntensity > 0;
        }, ModCases::clearPortal);

        add("Portals off: vanilla closes it (control)", 40, () -> {
            buildPortal();
            disable(Portals.class);
        }, () -> {
            if (caseTicks == 8) mc.gui.setScreen(new InventoryScreen(mc.player));
            return caseTicks >= 20 && mc.gui.screen() == null;
        }, ModCases::clearPortal);

        add("NoMiningTrace: crosshair hits the block behind an armour stand", 40, () -> {
            setupTrace();
            enable(NoMiningTrace.class);
        }, () -> {
            return mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK;
        }, ModCases::clearTrace);

        add("NoMiningTrace off: the stand is in the way (control)", 40, () -> {
            setupTrace();
            disable(NoMiningTrace.class);
        }, () -> {
            aimAtTraceStone();
            return mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY;
        }, ModCases::clearTrace);

        // ---- world -------------------------------------------------------------------------------------------
        add("AutoMount gets in a boat", 80, () -> {
            cmd(f("summon oak_boat %.1f %d %.1f", (px) + 0.5, py, (pz + 2) + 0.5));
            enable(AutoMount.class);
        }, () -> mc.player.isPassenger() && splayer().isPassenger(), () -> {
            disable(AutoMount.class);
            cmd("ride @p dismount");
            cmd("kill @e[type=oak_boat]");
        });

        add("AutoNametag names a cow", 80, () -> {
            cmd("give @p name_tag[custom_name=\"Bessie\"]");
            cmd(f("summon cow %.1f %d %.1f {NoAI:1b}", (px) + 0.5, py, (pz + 2) + 0.5));
            enable(AutoNametag.class);
        }, () -> {
            Cow cow = serverEntity(Cow.class);
            return cow != null && cow.hasCustomName() && cow.getCustomName().getString().equals("Bessie");
        }, () -> { disable(AutoNametag.class); cmd("kill @e[type=cow]"); });

        add("Flamethrower sets a cow on fire", 80, () -> {
            cmd("give @p flint_and_steel");
            cmd(f("summon cow %.1f %d %.1f {NoAI:1b}", (px) + 0.5, py, (pz + 3) + 0.5));
            enable(Flamethrower.class);
        }, () -> {
            Cow cow = serverEntity(Cow.class);
            return cow != null && cow.isOnFire();
        }, () -> {
            disable(Flamethrower.class);
            cmd("kill @e[type=cow]");
            cmd(f("fill %d %d %d %d %d %d air replace fire", px - 4, py, pz - 4, px + 4, py + 1, pz + 6));
        });

        add("EndermanLook: the server never gets a stare", 60, () -> {
            cmd(f("summon enderman %.1f %d %.1f {NoAI:1b}", (px) + 0.5, py, (pz + 6) + 0.5));
            enable(EndermanLook.class);
        }, () -> {
            lookAtEnderman();
            return caseTicks >= 30 && !serverStare() && cameraStare();
        }, () -> { disable(EndermanLook.class); cmd("kill @e[type=enderman]"); });

        add("EndermanLook off: the same look is a stare (control)", 60, () -> {
            cmd(f("summon enderman %.1f %d %.1f {NoAI:1b}", (px) + 0.5, py, (pz + 6) + 0.5));
            disable(EndermanLook.class);
        }, () -> {
            lookAtEnderman();
            return caseTicks >= 20 && serverStare();
        }, () -> cmd("kill @e[type=enderman]"));

        add("AutoSign writes a placed sign", 80, () -> {
            cmd("give @p oak_sign");
            AutoSign as = enable(AutoSign.class);
            as.line1.parse("Prism");
            as.line2.parse("Glass");
        }, () -> {
            BlockPos at = new BlockPos(px, py, pz + 2);
            if (caseTicks == 10) {
                mc.player.getInventory().setSelectedSlot(0);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(at.below()).add(0, 0.5, 0), Direction.UP, at.below(), false));
            }
            // the client's copy: AutoSign never writes it locally, so text here came back from the server
            return mc.level.getBlockEntity(at) instanceof SignBlockEntity sign
                && sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(0).getString().equals("Prism")
                && sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(1).getString().equals("Glass") && mc.gui.screen() == null;
        }, () -> { disable(AutoSign.class); cmd(f("setblock %d %d %d air", px, py, pz + 2)); });
    }

    static void registerBatch3() {
        // ---- render ------------------------------------------------------------------------------------------
        add("BreakIndicators shows our own mining", 60, () -> {
            cmd(f("setblock %d %d %d obsidian", px, py, pz + 2));
            cmd("give @p wooden_pickaxe");
            enable(BreakIndicators.class);
        }, () -> {
            BlockPos at = new BlockPos(px, py, pz + 2);
            if (caseTicks == 6) mc.gameMode.startDestroyBlock(at, Direction.NORTH);
            else if (caseTicks > 6) mc.gameMode.continueDestroyBlock(at, Direction.NORTH);
            return caseTicks > 20 && dev.prismglass.Prism.modules().get(BreakIndicators.class).getInfo() != null;
        }, () -> {
            mc.gameMode.stopDestroyBlock();
            disable(BreakIndicators.class);
            cmd(f("setblock %d %d %d air", px, py, pz + 2));
        });

        add("CityEsp finds 4 cityable blocks around a holed enemy", 60, () -> {
            cmd(f("fill %d %d %d %d %d %d obsidian", px - 1, py - 1, pz + 3, px + 1, py - 1, pz + 5));
            cmd(f("fill %d %d %d %d %d %d obsidian", px - 1, py, pz + 3, px + 1, py, pz + 5));
            cmd(f("setblock %d %d %d air", px, py, pz + 4));
            cmd(f("tp @p %.1f %d %.1f", (px) + 0.5, py, (pz + 4) + 0.5));
            enable(CityEsp.class);
        }, () -> {
            if (caseTicks == 10) dev.prismglass.Prism.fakePlayers().join("Holed");
            if (caseTicks == 12) cmd(f("tp @p %.1f %d %.1f", (px) + 0.5, py, (pz) + 0.5));
            return caseTicks > 20 && "4".equals(dev.prismglass.Prism.modules().get(CityEsp.class).getInfo());
        }, () -> {
            disable(CityEsp.class);
            dev.prismglass.Prism.fakePlayers().leave("Holed");
            cmd(f("fill %d %d %d %d %d %d air", px - 1, py, pz + 3, px + 1, py, pz + 5));
            cmd(f("fill %d %d %d %d %d %d grass_block", px - 1, py - 1, pz + 3, px + 1, py - 1, pz + 5));
        });

        add("TunnelESP finds a dug 1x2 tunnel", 80, () -> {
            cmd(f("fill %d %d %d %d %d %d stone", px + 3, py, pz + 3, px + 5, py + 3, pz + 12));
            cmd(f("fill %d %d %d %d %d %d air", px + 4, py, pz + 4, px + 4, py + 1, pz + 11));
            TunnelESP te = enable(TunnelESP.class);
            te.rescan.parse("2");
        }, () -> dev.prismglass.Prism.modules().get(TunnelESP.class).getInfo() != null, () -> {
            disable(TunnelESP.class);
            cmd(f("fill %d %d %d %d %d %d air", px + 3, py, pz + 3, px + 5, py + 3, pz + 12));
        });

        add("Lighting marks spawnable spots in a dark room", 60, () -> {
            cmd(f("fill %d %d %d %d %d %d stone hollow", px + 2, py - 1, pz + 2, px + 8, py + 3, pz + 8));
            enable(Lighting.class);
        }, () -> {
            // the 5x5 floor inside the stone box has no sky and no block light: spawns any time
            return dev.prismglass.Prism.modules().get(Lighting.class).alwaysCount() >= 20;
        }, () -> {
            disable(Lighting.class);
            cmd(f("fill %d %d %d %d %d %d air", px + 2, py, pz + 2, px + 8, py + 3, pz + 8));
            cmd(f("fill %d %d %d %d %d %d grass_block", px + 2, py - 1, pz + 2, px + 8, py - 1, pz + 8));
        });

        add("Markers add, persist and remove", 20, () -> {
            Markers mk = enable(Markers.class);
            mk.clear();
            mk.addSphere("ball", true, px, py, pz, 5);
            mk.addCuboid("box", px, py, pz, px + 3, py + 3, pz + 3);
        }, () -> {
            Markers mk = dev.prismglass.Prism.modules().get(Markers.class);
            boolean saved;
            try { saved = java.nio.file.Files.readString(dev.prismglass.Prism.config().folder().resolve("markers.json")).contains("\"ball\""); }
            catch (Exception e) { saved = false; }
            boolean ok = mk.markers().size() == 2 && saved && mk.remove("ball") && mk.markers().size() == 1;
            mk.clear();
            return ok;
        }, () -> disable(Markers.class));

        // ---- anarchy -----------------------------------------------------------------------------------------
        add("Spam sends, never repeats, and stays under the kick limit", 220, () -> {
            Spam sp = enable(Spam.class);
            sp.messages.parse("hello|world");
            sp.delay.parse("0.1");
        }, () -> {
            Spam sp = dev.prismglass.Prism.modules().get(Spam.class);
            // 10 messages fill vanilla's bucket, then one per second: about 20 in 200 ticks, never a kick
            return caseTicks >= 200 && sp.sent >= 12 && sp.sent <= 22 && mc.getConnection() != null && mc.level != null;
        }, () -> disable(Spam.class));
    }

    static void registerBatch4() {
        add("CustomCrosshair replaces the vanilla one", 30, () -> {
            CustomCrosshair cc = enable(CustomCrosshair.class);
            cc.shape.parse("Circle");
        }, () -> shot("crosshair", 15) && caseTicks > 20, () -> disable(CustomCrosshair.class));

        add("Ambience recolours the sky", 30, () -> enable(Ambience.class),
            () -> {
                int sky = net.minecraft.util.ARGB.colorFromVector3f(mc.gameRenderer.mainCamera().attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_COLOR, 1f));
                return shot("ambience", 12) && caseTicks > 15 && sky == (dev.prismglass.Prism.modules().get(Ambience.class).skyColor.color() | 0xFF000000);
            }, () -> disable(Ambience.class));

        add("HandView moves and shrinks the hand", 30, () -> {
            cmd("give @p diamond_sword");
            HandView hv = enable(HandView.class);
            hv.mainX.parse("-0.2"); hv.mainY.parse("0.1"); hv.mainScale.parse("0.6"); hv.mainRotZ.parse("30");
        }, () -> shot("handview", 15) && caseTicks > 20, () -> {
            HandView hv = dev.prismglass.Prism.modules().get(HandView.class);
            hv.mainX.parse("0"); hv.mainY.parse("0"); hv.mainScale.parse("1"); hv.mainRotZ.parse("0");
            disable(HandView.class);
        });

        add("SwordBlock: real shield block in the sword pose", 40, () -> {
            cmd("give @p diamond_sword");
            cmd("item replace entity @p weapon.offhand with shield");
            enable(SwordBlock.class);
        }, () -> {
            if (caseTicks == 8) mc.options.keyUse.setDown(true);
            boolean ok = caseTicks > 15 && mc.player.isBlocking() && dev.prismglass.Prism.modules().get(SwordBlock.class).blocking();
            return shot("swordblock", 20) && ok;
        }, () -> { mc.options.keyUse.setDown(false); disable(SwordBlock.class); });

        add("ItemHighlight tints totems in the inventory", 30, () -> {
            cmd("give @p totem_of_undying");
            cmd("give @p end_crystal 16");
            enable(ItemHighlight.class);
        }, () -> {
            if (caseTicks == 6) mc.gui.setScreen(new InventoryScreen(mc.player));
            return dev.prismglass.Prism.modules().get(ItemHighlight.class).wants(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING))
                && shot("itemhighlight", 15) && caseTicks > 18;
        }, () -> { mc.gui.setScreen(null); disable(ItemHighlight.class); });

        add("EntityOwner labels my tamed wolf", 40, () -> {
            int[] u = net.minecraft.core.UUIDUtil.uuidToIntArray(mc.player.getUUID());
            cmd(f("summon wolf %.1f %d %.1f {NoAI:1b,Owner:[I;%d,%d,%d,%d]}", (px) + 0.5, py, (pz + 3) + 0.5, u[0], u[1], u[2], u[3]));
            enable(EntityOwner.class);
        }, () -> shot("entityowner", 15) && "1".equals(dev.prismglass.Prism.modules().get(EntityOwner.class).getInfo()),
            () -> { disable(EntityOwner.class); cmd("kill @e[type=wolf]"); });

        add("Chams: a zombie behind a wall shows through it", 40, () -> {
            cmd(f("fill %d %d %d %d %d %d stone", px - 2, py, pz + 3, px + 2, py + 3, pz + 3));
            cmd(f("summon zombie %.1f %d %.1f {NoAI:1b}", (px) + 0.5, py, (pz + 5) + 0.5));
            Chams ch = enable(Chams.class);
            ch.hostiles.set(true);
        }, () -> shot("chams", 20) && caseTicks > 25, () -> {
            dev.prismglass.Prism.modules().get(Chams.class).hostiles.set(false);
            disable(Chams.class);
            cmd("kill @e[type=zombie]");
            cmd(f("fill %d %d %d %d %d %d air", px - 2, py, pz + 3, px + 2, py + 3, pz + 3));
        });

        add("PopChams leaves a ghost after a real totem pop", 80, () -> {
            cmd("item replace entity @p weapon.offhand with totem_of_undying");
            PopChams pc = enable(PopChams.class);
            pc.self.set(true);
            pc.duration.parse("4");
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        }, () -> {
            if (caseTicks == 10) { popSeen = -1; cmd("damage @p 1000 minecraft:generic"); }
            PopChams pc = dev.prismglass.Prism.modules().get(PopChams.class);
            // step aside once the ghost exists, so the shot shows it standing alone where we popped
            if (pc.ghostCount() > 0 && popSeen < 0) {
                popSeen = caseTicks;
                cmd(f("tp @p %.1f %d %.1f 90 0", (px + 4) + 0.5, py, (pz) + 0.5));
            }
            if (popSeen >= 0 && caseTicks >= popSeen + 45) shot("popchams", 0); // after the totem animation
            return pc.ghostCount() > 0 && shotTaken("popchams");
        }, () -> {
            mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            PopChams pc = dev.prismglass.Prism.modules().get(PopChams.class);
            pc.self.set(false);
            pc.duration.parse("1.5");
            disable(PopChams.class);
            cmd("effect give @p instant_health 1 5");
        });
    }

    static void registerBatch5() {
        for (String preset : new String[]{"Vanilla", "Grim"}) {
            add("AntiAnvil roofs over a falling anvil (" + preset + ")", 80, () -> {
                preset(preset);
                hole(px, py, pz);
                cmd(f("setblock %d %d %d obsidian", px + 1, py + 1, pz)); // one head-high wall to build from
                cmd("give @p obsidian 16");
                enable(dev.prismglass.module.combat.AntiAnvil.class);
            }, () -> {
                if (caseTicks == 6) cmd(f("summon falling_block %.1f %d %.1f {BlockState:{Name:\"minecraft:anvil\"},Time:1}", (px) + 0.5, py + 10, (pz) + 0.5));
                var roof = level().getBlockState(new BlockPos(px, py + 2, pz));
                boolean anvilGone = serverEntity(net.minecraft.world.entity.item.FallingBlockEntity.class) == null;
                boolean safe = !level().getBlockState(new BlockPos(px, py, pz)).is(net.minecraft.tags.BlockTags.ANVIL)
                    && !level().getBlockState(new BlockPos(px, py + 1, pz)).is(net.minecraft.tags.BlockTags.ANVIL);
                return caseTicks > 30 && anvilGone && safe && roof.is(net.minecraft.world.level.block.Blocks.OBSIDIAN);
            }, () -> { disable(dev.prismglass.module.combat.AntiAnvil.class); preset("Grim"); clearArea(); });
        }

        add("SelfAnvil burrows an anvil into my feet", 80, () -> {
            hole(px, py, pz);
            cmd(f("fill %d %d %d %d %d %d obsidian", px + 1, py + 1, pz, px + 1, py + 3, pz));
            cmd("give @p anvil 2");
            cmd("give @p obsidian 8");
            enable(dev.prismglass.module.combat.SelfAnvil.class);
        }, () -> level().getBlockState(new BlockPos(px, py, pz)).is(net.minecraft.tags.BlockTags.ANVIL),
            () -> { disable(dev.prismglass.module.combat.SelfAnvil.class); clearArea(); cmd("effect give @p instant_health 1 5"); });

        add("AutoAnvil drops an anvil on a holed enemy", 100, () -> {
            hole(px, py, pz + 3);
            cmd(f("fill %d %d %d %d %d %d obsidian", px + 1, py + 1, pz + 3, px + 1, py + 4, pz + 3));
            cmd("give @p anvil 3");
            cmd("give @p stone_button 4");
            cmd("give @p obsidian 8");
        }, () -> {
            // into the hole once it's built, leave the dummy there (only once we really stand in it), step back out
            BlockPos holeAt = new BlockPos(px, py, pz + 3);
            if (caseTicks == 5 || (caseTicks > 5 && caseTicks < 40 && dummyAt < 0 && !mc.player.blockPosition().equals(holeAt) && caseTicks % 5 == 0))
                cmd(f("tp @p %.1f %d %.1f", (px) + 0.5, py, (pz + 3) + 0.5));
            if (dummyAt < 0 && caseTicks > 5 && mc.player.blockPosition().equals(holeAt) && mc.player.onGround()) {
                dev.prismglass.Prism.fakePlayers().join("AnvilMe");
                dummyAt = caseTicks;
                cmd(f("tp @p %.1f %d %.1f", (px) + 0.5, py, (pz) + 0.5));
            }
            if (dummyAt >= 0 && caseTicks == dummyAt + 8) enable(dev.prismglass.module.combat.AutoAnvil.class);
            var aa = dev.prismglass.Prism.modules().get(dev.prismglass.module.combat.AutoAnvil.class);
            boolean anvilSeen = serverEntity(net.minecraft.world.entity.item.FallingBlockEntity.class) != null
                || level().getBlockState(new BlockPos(px, py, pz + 3)).is(net.minecraft.tags.BlockTags.ANVIL)
                || anyItem(net.minecraft.world.item.Items.ANVIL);
            return aa.isEnabled() && aa.dropped >= 1 && anvilSeen;
        }, () -> {
            disable(dev.prismglass.module.combat.AutoAnvil.class);
            dev.prismglass.Prism.fakePlayers().leave("AnvilMe");
            dummyAt = -1;
            cmd("kill @e[type=item]");
            clearArea();
        });

        add("AntiCity extends, then replaces a mined surround block", 80, () -> {
            hole(px, py, pz);
            cmd("give @p obsidian 16");
            enable(dev.prismglass.module.combat.AntiCity.class);
        }, () -> {
            BlockPos mined = new BlockPos(px + 1, py, pz);
            if (caseTicks >= 6 && caseTicks < 40) mc.level.destroyBlockProgress(4242, mined, 5); // "someone" mining it
            if (caseTicks == 30) cmd(f("setblock %d %d %d air", px + 1, py, pz));
            boolean extended = level().getBlockState(new BlockPos(px + 2, py, pz)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN);
            boolean replaced = caseTicks > 32 && level().getBlockState(mined).is(net.minecraft.world.level.block.Blocks.OBSIDIAN);
            return extended && replaced;
        }, () -> { mc.level.destroyBlockProgress(4242, new BlockPos(px + 1, py, pz), -1); disable(dev.prismglass.module.combat.AntiCity.class); clearArea(); });

        add("Flatten fills holes in the floor", 80, () -> {
            cmd(f("fill %d %d %d %d %d %d air", px + 1, py - 1, pz + 1, px + 2, py - 1, pz + 2));
            cmd("give @p obsidian 16");
            enable(dev.prismglass.module.combat.Flatten.class);
        }, () -> {
            for (int x = px + 1; x <= px + 2; x++) for (int z = pz + 1; z <= pz + 2; z++)
                if (!level().getBlockState(new BlockPos(x, py - 1, z)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) return false;
            return true;
        }, () -> { disable(dev.prismglass.module.combat.Flatten.class); clearArea(); });

        add("AutoWither builds a wither", 80, () -> {
            cmd("give @p soul_sand 4");
            cmd("give @p wither_skeleton_skull 3");
        }, () -> {
            if (caseTicks == 8) enable(dev.prismglass.module.combat.AutoWither.class);
            return serverEntity(net.minecraft.world.entity.boss.wither.WitherBoss.class) != null;
        }, () -> { cmd("kill @e[type=wither]"); disable(dev.prismglass.module.combat.AutoWither.class); clearArea(); });
    }

    static void registerBatch6() {
        add("ShieldBypass hits a blocking player with the axe", 60, () -> {
            cmd("item replace entity @p weapon.offhand with shield");
        }, () -> {
            if (caseTicks == 6) dev.prismglass.Prism.fakePlayers().join("Blocker");
            if (caseTicks == 8) {
                cmd("item replace entity @p weapon.offhand with air");
                cmd("item replace entity @p hotbar.0 with diamond_sword");
                cmd("item replace entity @p hotbar.1 with diamond_axe");
                cmd(f("tp @p %.1f %d %.1f 180 0", (px) + 0.5, py, (pz + 3) + 0.5));
            }
            var fake = mc.level.players().stream().filter(pl -> pl.getName().getString().equals("Blocker")).findFirst().orElse(null);
            if (fake == null) return false;
            if (caseTicks == 12) {
                fake.setYRot(0); fake.setYHeadRot(0); fake.yBodyRot = 0; // facing south, at us
                fake.startUsingItem(InteractionHand.OFF_HAND);
                // the server syncs "using an item" as a living-entity flag; a client-only dummy needs it set by hand
                try {
                    var f = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var key = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) f.get(null);
                    fake.getEntityData().set(key, (byte) 3); // using + offhand
                } catch (ReflectiveOperationException e) { log("FAIL reflection " + e); }
                mc.player.getInventory().setSelectedSlot(0);
                enable(dev.prismglass.module.combat.ShieldBypass.class);
            }
            if (caseTicks < 22 || !fake.isBlocking()) return false;
            mc.player.getInventory().setSelectedSlot(0);
            mc.gameMode.attack(mc.player, fake);
            return mc.player.getInventory().getSelectedSlot() == 1; // switched to the axe for that hit
        }, () -> { disable(dev.prismglass.module.combat.ShieldBypass.class); dev.prismglass.Prism.fakePlayers().leave("Blocker"); });

        add("AutoMend puts a damaged Mending pick in the offhand and mends it", 120, () -> {
            cmd("item replace entity @p hotbar.0 with dirt");
            cmd("item replace entity @p hotbar.1 with diamond_pickaxe[damage=1200,enchantments={mending:1}]");
            cmd("item replace entity @p hotbar.2 with experience_bottle 64");
            mc.player.getInventory().setSelectedSlot(0); // holding the pick would already mend it in the main hand
            enable(dev.prismglass.module.combat.AutoMend.class);
        }, () -> {
            var off = splayer().getOffhandItem();
            return off.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE) && off.getDamageValue() < 1150;
        }, () -> { disable(dev.prismglass.module.combat.AutoMend.class); cmd("kill @e[type=experience_orb]"); });

        add("PistonAura pushes a crystal into a holed zombie and blows it up", 160, () -> {
            hole(px, py, pz + 2);
            cmd(f("summon zombie %.1f %d %.1f {NoAI:1b,PersistenceRequired:1b}", (px) + 0.5, py, (pz + 2) + 0.5));
            cmd("effect give @p resistance 60 4 true");
            cmd("give @p piston 4");
            cmd("give @p redstone_torch 8");
            cmd("give @p end_crystal 8");
            cmd("give @p obsidian 16");
            var pa = enable(dev.prismglass.module.combat.PistonAura.class);
            pa.mobs.set(true); pa.maxSelf.parse("36"); pa.antiSuicide.set(false);
        }, () -> {
            var pa = dev.prismglass.Prism.modules().get(dev.prismglass.module.combat.PistonAura.class);
            var z = serverEntity(net.minecraft.world.entity.monster.zombie.Zombie.class);
            return pa.detonations >= 1 && (z == null || z.getHealth() < 10);
        }, () -> {
            var pa = dev.prismglass.Prism.modules().get(dev.prismglass.module.combat.PistonAura.class);
            pa.mobs.set(false); pa.maxSelf.parse("8"); pa.antiSuicide.set(true);
            disable(dev.prismglass.module.combat.PistonAura.class);
            cmd("kill @e[type=zombie]"); cmd("kill @e[type=end_crystal]"); cmd("kill @e[type=item]"); cmd("effect clear @p");
            clearArea();
        });

        add("PistonPush shoves a zombie on open ground", 100, () -> {
            cmd(f("summon zombie %.1f %d %.1f {NoAI:1b,PersistenceRequired:1b}", (px) + 0.5, py, (pz + 2) + 0.5));
            cmd("give @p piston 4");
            cmd("give @p redstone_torch 8");
            cmd("give @p obsidian 16");
            var pp = enable(dev.prismglass.module.combat.PistonPush.class);
            pp.mobs.set(true);
        }, () -> {
            var z = serverEntity(net.minecraft.world.entity.monster.zombie.Zombie.class);
            return z != null && z.getZ() < pz + 2.5 - 0.7; // shoved a block toward us
        }, () -> {
            var pp = dev.prismglass.Prism.modules().get(dev.prismglass.module.combat.PistonPush.class);
            pp.mobs.set(false);
            disable(dev.prismglass.module.combat.PistonPush.class);
            cmd("kill @e[type=zombie]");
            clearArea();
        });
    }

    static void registerBatch7() {
        add("Services survive a profile switch and panic", 40, () -> {
            enable(dev.prismglass.module.service.DamageAILearn.class);
        }, () -> {
            var ai = dev.prismglass.Prism.modules().get(dev.prismglass.module.service.DamageAILearn.class);
            String before = dev.prismglass.Prism.config().getActive();
            try { dev.prismglass.Prism.config().switchTo("aitest"); } catch (Exception e) { log("FAIL switch " + e); return false; }
            boolean afterSwitch = ai.isEnabled();
            dev.prismglass.Prism.modules().get(dev.prismglass.module.client.KeyActions.class).actions().stream()
                .filter(x -> x.name().equals("Panic")).findFirst().ifPresent(x -> x.run().run());
            boolean afterPanic = ai.isEnabled();
            try { dev.prismglass.Prism.config().switchTo(before); } catch (Exception ignored) {}
            dev.prismglass.Prism.config().delete("aitest");
            boolean saved;
            try { saved = java.nio.file.Files.readString(dev.prismglass.Prism.config().folder().resolve("services.json")).contains("\"enabled\": true"); }
            catch (Exception e) { saved = false; }
            log(f("services: switch=%s panic=%s saved=%s", afterSwitch, afterPanic, saved));
            return afterSwitch && afterPanic && saved;
        }, () -> {});

        add("Config tab shows the Services section", 30, () -> {
            enable(dev.prismglass.module.service.DamageAILearn.class);
            var gui = new dev.prismglass.gui.ClickGuiScreen();
            mc.gui.setScreen(gui);
        }, () -> {
            if (caseTicks == 4 && mc.gui.screen() instanceof dev.prismglass.gui.ClickGuiScreen g) g.debugMenu(1);
            return shot("services", 14) && caseTicks > 16;
        }, () -> { mc.gui.setScreen(null); disable(dev.prismglass.module.service.DamageAILearn.class); });

        add("Damage AI learns against real zombies", 700, () -> {
            var ai = enable(dev.prismglass.module.service.DamageAILearn.class);
            ai.learnMobs.set(true);
            ai.episode.parse("1.5");
            ai.forget("Zombie");
            cmd("give @p diamond_sword");
            cmd("effect give @p resistance 60 3 true");
            cmd("effect give @p regeneration 60 2 true");
            for (int i = 0; i < 2; i++)
                cmd(f("summon zombie %.1f %d %.1f {PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:300}],Health:300f}", (px + 5) + 0.5, py, (pz + 3 * i) + 0.5));
            var ka = enable(dev.prismglass.module.combat.KillAura.class);
            ka.hostiles.set(true); ka.players.set(false); ka.useAI.set(true);
        }, () -> {
            if (caseTicks < 680) return false;
            var ai = dev.prismglass.Prism.modules().get(dev.prismglass.module.service.DamageAILearn.class);
            var p = ai.profile("Zombie");
            long tried = p.tactics.values().stream().filter(st -> st.n > 0).count();
            ai.save();
            boolean file = java.nio.file.Files.exists(dev.prismglass.Prism.config().folder().resolve("ai").resolve("opponents.json"));
            log("ai " + ai.summary("Zombie"));
            log(f("ai: landed=%d taken=%d tactics tried=%d file=%s", p.hitsLanded, p.hitsTaken, tried, file));
            for (var e : p.tactics.entrySet()) log(f("ai   %-10s n=%d mean=%+.2f", e.getKey().label, e.getValue().n, e.getValue().mean));
            if (!(p.hitsLanded > 5 && tried >= 5 && file)) caseTicks = 10_000; // report once, then fail
            return p.hitsLanded > 5 && tried >= 5 && file;
        }, () -> {
            var ka = dev.prismglass.Prism.modules().get(dev.prismglass.module.combat.KillAura.class);
            ka.hostiles.set(false); ka.players.set(true);
            disable(dev.prismglass.module.combat.KillAura.class);
            var ai = dev.prismglass.Prism.modules().get(dev.prismglass.module.service.DamageAILearn.class);
            ai.learnMobs.set(false); ai.episode.parse("3");
            disable(dev.prismglass.module.service.DamageAILearn.class);
            cmd("kill @e[type=zombie]"); cmd("effect clear @p");
        });
    }

    static void registerNoFall() {
        for (boolean on : new boolean[]{true, false}) {
            add(on ? "NoFall: fly up, fly down, land - no damage" : "Flight landing without NoFall hurts (control)", 160, () -> {
                cmd("effect clear @p");
                var fl = enable(dev.prismglass.module.movement.Flight.class);
                fl.mode.parse("Vanilla");
                if (on) enable(dev.prismglass.module.player.NoFall.class); else disable(dev.prismglass.module.player.NoFall.class);
            }, () -> {
                // up 20 blocks, hover, then down with Shift until we touch the ground
                if (caseTicks == 4) mc.options.keyJump.setDown(true);
                if (caseTicks == 40) mc.options.keyJump.setDown(false);
                if (caseTicks == 60) mc.options.keyShift.setDown(true);
                if (caseTicks > 60 && mc.player.onGround() && caseTicks < 150) {
                    mc.options.keyShift.setDown(false);
                    disable(dev.prismglass.module.movement.Flight.class);
                    caseTicks = 150;
                }
                if (caseTicks < 155) return false;
                float hp = splayer().getHealth();
                log(f("nofall %s: health %.1f", on, hp));
                return on ? hp >= 20f : hp < 20f;
            }, () -> {
                mc.options.keyJump.setDown(false); mc.options.keyShift.setDown(false);
                disable(dev.prismglass.module.movement.Flight.class);
                disable(dev.prismglass.module.player.NoFall.class);
                cmd("effect give @p instant_health 1 5");
            });
        }

        add("NoFall Packet: a 15 block drop does nothing", 80, () -> {
            var nf = enable(dev.prismglass.module.player.NoFall.class);
            nf.mode.parse("Packet");
            cmd(f("tp @p %.1f %d %.1f", (px) + 0.5, py + 15, (pz) + 0.5));
        }, () -> {
            if (caseTicks < 60) return false;
            log(f("nofall packet: health %.1f", splayer().getHealth()));
            return splayer().getHealth() >= 20f && mc.player.onGround();
        }, () -> {
            var nf = dev.prismglass.Prism.modules().get(dev.prismglass.module.player.NoFall.class);
            nf.mode.parse("Bucket");
            disable(dev.prismglass.module.player.NoFall.class);
        });
    }

    static void preset(String name) {
        dev.prismglass.Prism.anticheat().preset.parse(name);
        dev.prismglass.Prism.anticheat().update();
    }

    /** Obsidian surround (and floor) around a spot. */
    static void hole(int x, int y, int z) {
        cmd(f("fill %d %d %d %d %d %d obsidian", x - 1, y - 1, z - 1, x + 1, y - 1, z + 1));
        cmd(f("fill %d %d %d %d %d %d obsidian", x - 1, y, z - 1, x + 1, y, z + 1));
        cmd(f("setblock %d %d %d air", x, y, z));
    }

    /** Back to a flat grass floor around the start. */
    static void clearArea() {
        cmd(f("fill %d %d %d %d %d %d air", px - 6, py, pz - 6, px + 6, py + 8, pz + 8));
        cmd(f("fill %d %d %d %d %d %d grass_block", px - 6, py - 1, pz - 6, px + 6, py - 1, pz + 8));
    }

    static boolean anyItem(net.minecraft.world.item.Item item) {
        for (var e : level().getAllEntities()) if (e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.getItem().is(item)) return true;
        return false;
    }

    private static final java.util.Set<String> shots = new java.util.HashSet<>();
    private static int popSeen = -1, dummyAt = -1;

    /** Screenshot run/screenshots/modshot-NAME.png once, at or after case tick {@code at}; true once taken. */
    static boolean shot(String name, int at) {
        if (shots.contains(name)) return true;
        if (caseTicks < at) return false;
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, "modshot-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
        shots.add(name);
        return true;
    }

    static boolean shotTaken(String name) { return shots.contains(name); }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** An x-axis portal 2 blocks south with the player standing in it. */
    private static void buildPortal() {
        cmd(f("fill %d %d %d %d %d %d obsidian", px - 1, py - 1, pz + 2, px + 2, py + 3, pz + 2));
        cmd(f("fill %d %d %d %d %d %d nether_portal[axis=x]", px, py, pz + 2, px + 1, py + 2, pz + 2));
        cmd(f("tp @p %.1f %d %.1f 0 0", (px) + 0.5, py, (pz + 2) + 0.5));
    }

    private static void clearPortal() {
        mc.gui.setScreen(null);
        cmd(f("tp @p %.1f %d %.1f 0 0", (px) + 0.5, py, (pz) + 0.5));
        cmd(f("fill %d %d %d %d %d %d air", px - 1, py, pz + 2, px + 2, py + 3, pz + 2));
        cmd(f("fill %d %d %d %d %d %d grass_block", px - 1, py - 1, pz + 2, px + 2, py - 1, pz + 2));
        disable(Portals.class);
    }

    /** Stone 3 blocks south, an armour stand between; pickaxe in hand; looking at the stone through the stand. */
    private static void setupTrace() {
        cmd("give @p diamond_pickaxe");
        cmd(f("fill %d %d %d %d %d %d stone", px, py, pz + 3, px, py + 1, pz + 3));
        cmd(f("summon armor_stand %.1f %d %.1f {NoGravity:1b}", (px) + 0.5, py, (pz + 1) + 0.5));
    }

    /** Look at the middle of the stone (the setup's tp resets the rotation when it lands). */
    private static void aimAtTraceStone() {
        float[] r = dev.prismglass.util.RotationUtil.toward(new Vec3(px + 0.5, py + 1.0, pz + 3));
        mc.player.setYRot(r[0]);
        mc.player.setXRot(r[1]);
    }

    private static void clearTrace() {
        disable(NoMiningTrace.class);
        cmd("kill @e[type=armor_stand]");
        cmd(f("fill %d %d %d %d %d %d air", px, py, pz + 3, px, py + 1, pz + 3));
    }

    private static void lookAtEnderman() {
        Enderman em = null;
        for (var e : mc.level.entitiesForRendering()) if (e instanceof Enderman m) em = m;
        if (em == null) return;
        float[] r = dev.prismglass.util.RotationUtil.toward(new Vec3(em.getX(), em.getEyeY(), em.getZ()));
        mc.player.setYRot(r[0]);
        mc.player.setXRot(r[1]);
    }

    private static boolean cameraStare() { return stare(mc.player.getViewVector(1), mc.player.getEyePosition(), clientEnderman()); }

    private static boolean serverStare() {
        Enderman em = serverEntity(Enderman.class);
        return em != null && stare(splayer().getViewVector(1), splayer().getEyePosition(), new Vec3(em.getX(), em.getEyeY(), em.getZ()));
    }

    private static Vec3 clientEnderman() {
        for (var e : mc.level.entitiesForRendering()) if (e instanceof Enderman m) return new Vec3(m.getX(), m.getEyeY(), m.getZ());
        return null;
    }

    /** Enderman.isLookingAtMe's cone (0.025, distance-adjusted); line of sight is open here. */
    private static boolean stare(Vec3 look, Vec3 eye, Vec3 target) {
        if (target == null) return false;
        Vec3 dir = target.subtract(eye);
        double dist = dir.length();
        return look.normalize().dot(dir.normalize()) > 1.0 - 0.025 / dist;
    }
}

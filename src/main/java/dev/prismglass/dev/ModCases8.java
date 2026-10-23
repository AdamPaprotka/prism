package dev.prismglass.dev;

import static dev.prismglass.dev.ModTest.*;
import static dev.prismglass.dev.ModCases.clearArea;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** ModTest cases for the batch-8 modules (movement / player / VeinMiner). */
final class ModCases8 {
    private static final Minecraft mc = Minecraft.getInstance();
    private static double slideFrom;

    private ModCases8() {}

    /** Points the camera at a spot (client rotation; modules read it from there). */
    static void aim(Vec3 at) {
        float[] r = dev.prismglass.util.RotationUtil.toward(mc.player.getEyePosition(), at);
        mc.player.setYRot(r[0]); mc.player.setXRot(r[1]);
        mc.player.yRotO = r[0]; mc.player.xRotO = r[1];
    }

    /** Right-clicks a block's top face (opens tables, furnaces...). */
    static void open(BlockPos pos) {
        aim(pos.getCenter().add(0, 0.5, 0));
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(pos.getCenter().add(0, 0.5, 0), Direction.UP, pos, false));
    }

    static void register() {
        add("ClickTP: right-click a block 20 away and stand on it", 60, () -> {
            clearArea();
            enable(dev.prismglass.module.movement.ClickTP.class);
        }, () -> {
            Vec3 top = new Vec3(px + 0.5, py, pz + 20.5);
            if (caseTicks >= 4 && caseTicks <= 8) aim(top);
            if (caseTicks == 6) mc.options.keyUse.setDown(true);
            if (caseTicks == 8) mc.options.keyUse.setDown(false);
            if (caseTicks < 20) return false;
            Vec3 sp = splayer().position();
            log(f("clicktp: server at %.2f %.2f %.2f", sp.x, sp.y, sp.z));
            return Math.abs(sp.z - (pz + 20.5)) < 0.6 && Math.abs(sp.x - (px + 0.5)) < 0.6 && Math.abs(sp.y - py) < 0.2;
        }, () -> { mc.options.keyUse.setDown(false); disable(dev.prismglass.module.movement.ClickTP.class); });

        add("ElytraBoost: fires a rocket when gliding slowly", 120, () -> {
            cmd("item replace entity @p armor.chest with elytra");
            cmd("give @p firework_rocket 16");
            cmd("effect give @p resistance 30 4 true");
            cmd(f("tp @p %.1f %d %.1f 0 0", (px) + 0.5, py + 40, (pz) + 0.5));
            enable(dev.prismglass.module.movement.ElytraBoost.class).mode.parse("Rocket");
        }, () -> {
            if (caseTicks == 10) mc.options.keyJump.setDown(true);
            if (caseTicks == 12) mc.options.keyJump.setDown(false);
            if (caseTicks < 40) return false;
            int left = mc.player.getInventory().countItem(Items.FIREWORK_ROCKET);
            log(f("elytraboost: flying=%s rockets=%d speed=%.1f", mc.player.isFallFlying(), left, mc.player.getDeltaMovement().length() * 20));
            return left < 16 && left > 0;
        }, () -> {
            mc.options.keyJump.setDown(false);
            disable(dev.prismglass.module.movement.ElytraBoost.class);
            cmd("kill @e[type=firework_rocket]"); cmd("clear @p"); cmd("effect clear @p");
        });

        add("EntityControl: steer a horse with no saddle", 80, () -> {
            clearArea();
            cmd(f("summon horse %.1f %d %.1f {Tame:1b,Rotation:[0f,0f]}", (px) + 0.5, py, (pz) + 0.5));
            enable(dev.prismglass.module.movement.EntityControl.class);
        }, () -> {
            if (caseTicks == 6) cmd("ride @p mount @e[type=horse,limit=1,sort=nearest]");
            if (caseTicks == 12) { mc.player.setYRot(0); mc.options.keyUp.setDown(true); }
            if (caseTicks < 50) return false;
            mc.options.keyUp.setDown(false);
            var v = mc.player.getVehicle();
            if (v == null) { log("entitycontrol: not riding"); return false; }
            double moved = v.position().distanceTo(new Vec3(px + 0.5, py, pz + 0.5));
            log(f("entitycontrol: horse moved %.2f client", moved));
            return moved > 3;
        }, () -> {
            mc.options.keyUp.setDown(false);
            disable(dev.prismglass.module.movement.EntityControl.class);
            mc.player.stopRiding();
            cmd("kill @e[type=horse]");
        });

        add("Follow: walks after a player who moves away", 200, () -> {
            clearArea();
            dev.prismglass.Prism.fakePlayers().join("Leader");
        }, () -> {
            // fake players live on the client only: move the entity itself
            var leader = mc.level.players().stream().filter(pl -> pl.getName().getString().equals("Leader")).findFirst().orElse(null);
            if (leader == null) return false;
            if (caseTicks == 10) {
                leader.snapTo(px + 0.5, py, pz + 6.5, 0, 0);
                dev.prismglass.Prism.modules().get(dev.prismglass.module.movement.Follow.class).follow("Leader");
            }
            if (caseTicks == 60) leader.snapTo(px + 4.5, py, pz + 8.5, 0, 0);
            if (caseTicks < 130) return false;
            double d = mc.player.position().distanceTo(new Vec3(px + 4.5, py, pz + 8.5));
            log(f("follow: %.2f from leader", d));
            return d < 3.6 && d > 1.2;
        }, () -> { disable(dev.prismglass.module.movement.Follow.class); dev.prismglass.Prism.fakePlayers().leave("Leader"); });

        for (boolean on : new boolean[]{true, false}) {
            add(on ? "Slippy: keep sliding after letting go of W" : "No Slippy: stops right away (control)", 70, () -> {
                clearArea();
                if (on) enable(dev.prismglass.module.movement.Slippy.class); else disable(dev.prismglass.module.movement.Slippy.class);
            }, () -> {
                if (caseTicks == 4) { mc.player.setYRot(0); mc.options.keyUp.setDown(true); }
                if (caseTicks == 24) { mc.options.keyUp.setDown(false); slideFrom = mc.player.getZ(); }
                if (caseTicks < 50) return false;
                double slid = mc.player.getZ() - slideFrom;
                log(f("slippy %s: slid %.2f", on, slid));
                return on ? slid > 1.5 : slid < 0.6;
            }, () -> { mc.options.keyUp.setDown(false); disable(dev.prismglass.module.movement.Slippy.class); });
        }

        add("VeinMiner: breaking one ore mines the vein", 300, () -> {
            clearArea();
            cmd("give @p diamond_pickaxe[enchantments={efficiency:5}]");
            cmd(f("fill %d %d %d %d %d %d iron_ore", px, py, pz + 2, px, py, pz + 4));
            cmd(f("setblock %d %d %d deepslate_iron_ore", px + 1, py + 1, pz + 4));
            cmd(f("setblock %d %d %d stone", px - 1, py, pz + 2));
            enable(dev.prismglass.module.world.VeinMiner.class);
        }, () -> {
            BlockPos first = new BlockPos(px, py, pz + 2);
            if (caseTicks == 6) mc.player.getInventory().setSelectedSlot(0);
            if (caseTicks >= 6 && caseTicks < 40 && mc.level.getBlockState(first).is(Blocks.IRON_ORE)) {
                aim(new Vec3(px + 0.5, py + 0.5, pz + 2));
                mc.gameMode.continueDestroyBlock(first, Direction.NORTH);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            if (caseTicks < 40) return false;
            int left = 0;
            for (BlockPos b : new BlockPos[]{first, new BlockPos(px, py, pz + 3), new BlockPos(px, py, pz + 4), new BlockPos(px + 1, py + 1, pz + 4)})
                if (!level().getBlockState(b).isAir()) left++;
            boolean stone = level().getBlockState(new BlockPos(px - 1, py, pz + 2)).is(Blocks.STONE);
            if (left > 0 && caseTicks < 280) return false;
            log(f("veinminer: %d ore left, stone kept=%s", left, stone));
            return left == 0 && stone;
        }, () -> { disable(dev.prismglass.module.world.VeinMiner.class); clearArea(); });

        add("GhostHand: opens a chest behind a wall", 40, () -> {
            clearArea();
            cmd(f("fill %d %d %d %d %d %d stone", px - 1, py, pz + 2, px + 1, py + 2, pz + 2));
            cmd(f("setblock %d %d %d chest", px, py + 1, pz + 3));
            enable(dev.prismglass.module.player.GhostHand.class);
        }, () -> {
            if (caseTicks >= 6 && caseTicks <= 10) aim(new Vec3(px + 0.5, py + 1.5, pz + 3.5));
            if (caseTicks == 8) mc.options.keyUse.setDown(true);
            if (caseTicks == 10) mc.options.keyUse.setDown(false);
            if (caseTicks < 24) return false;
            log("ghosthand: screen " + mc.screen);
            return mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen;
        }, () -> { mc.options.keyUse.setDown(false); mc.player.closeContainer(); disable(dev.prismglass.module.player.GhostHand.class); clearArea(); });

        for (boolean block : new boolean[]{false, true}) {
            add(block ? "NoInteract: a block in hand places against the crafting table" : "NoInteract: crafting table does not open", 40, () -> {
                clearArea();
                cmd(f("setblock %d %d %d crafting_table", px, py, pz + 2));
                if (block) cmd("give @p cobblestone 8");
                enable(dev.prismglass.module.player.NoInteract.class);
            }, () -> {
                if (caseTicks == 8) {
                    mc.player.getInventory().setSelectedSlot(0);
                    aim(new Vec3(px + 0.5, py + 1, pz + 2.5));
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(px + 0.5, py + 1, pz + 2.5), Direction.UP, new BlockPos(px, py, pz + 2), false));
                }
                if (caseTicks < 24) return false;
                boolean placed = level().getBlockState(new BlockPos(px, py + 1, pz + 2)).is(Blocks.COBBLESTONE);
                boolean sneaking = splayer().isShiftKeyDown();
                log(f("nointeract: screen=%s placed=%s serverSneak=%s", mc.screen, placed, sneaking));
                return mc.screen == null && placed == block && !sneaking;
            }, () -> { mc.setScreen(null); disable(dev.prismglass.module.player.NoInteract.class); clearArea(); });
        }

        add("InventoryTweaks: sort the main inventory", 120, () -> {
            cmd("item replace entity @p inventory.0 with stone 5");
            cmd("item replace entity @p inventory.4 with apple 3");
            cmd("item replace entity @p inventory.9 with stone 40");
            cmd("item replace entity @p inventory.13 with arrow 7");
            cmd("item replace entity @p inventory.20 with apple 9");
            cmd("item replace entity @p hotbar.0 with diamond_sword");
            enable(dev.prismglass.module.player.InventoryTweaks.class);
        }, () -> {
            var it = dev.prismglass.Prism.modules().get(dev.prismglass.module.player.InventoryTweaks.class);
            if (caseTicks == 8) { mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)); }
            if (caseTicks == 12) it.startSort(mc.player.inventoryMenu.getSlot(20));
            if (caseTicks < 16 || it.sorting()) return false;
            var inv = mc.player.getInventory();
            StringBuilder sb = new StringBuilder();
            for (int i = 9; i < 15; i++) sb.append(inv.getItem(i).getCount()).append(' ').append(inv.getItem(i).getItem()).append(", ");
            log("sort: " + sb + "hotbar0=" + inv.getItem(0).getItem());
            return inv.getItem(9).is(Items.APPLE) && inv.getItem(9).getCount() == 9 && inv.getItem(10).is(Items.APPLE)
                && inv.getItem(11).is(Items.ARROW) && inv.getItem(12).is(Items.STONE) && inv.getItem(12).getCount() == 40
                && inv.getItem(13).is(Items.STONE) && inv.getItem(14).isEmpty() && inv.getItem(0).is(Items.DIAMOND_SWORD);
        }, () -> { mc.setScreen(null); disable(dev.prismglass.module.player.InventoryTweaks.class); });

        add("InventoryTweaks: AntiDrop keeps the totem, AutoDrop throws junk", 60, () -> {
            cmd("item replace entity @p hotbar.0 with totem_of_undying");
            cmd("item replace entity @p inventory.5 with rotten_flesh 12");
            var it = enable(dev.prismglass.module.player.InventoryTweaks.class);
            it.autoDrop.set(true);
        }, () -> {
            if (caseTicks == 8) { mc.player.getInventory().setSelectedSlot(0); mc.player.drop(true); }
            if (caseTicks < 40) return false;
            boolean totem = mc.player.getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1 && splayer().getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1;
            int flesh = splayer().getInventory().countItem(Items.ROTTEN_FLESH);
            log(f("antidrop: totem kept=%s flesh left=%d", totem, flesh));
            return totem && flesh == 0;
        }, () -> {
            dev.prismglass.Prism.modules().get(dev.prismglass.module.player.InventoryTweaks.class).autoDrop.set(false);
            disable(dev.prismglass.module.player.InventoryTweaks.class);
            cmd("kill @e[type=item]");
        });

        add("LiquidFiller: fills lava sources", 60, () -> {
            clearArea();
            cmd(f("setblock %d %d %d lava", px, py - 1, pz + 2));
            cmd(f("setblock %d %d %d lava", px + 2, py - 1, pz + 1));
            cmd("give @p netherrack 16");
            enable(dev.prismglass.module.world.LiquidFiller.class);
        }, () -> {
            if (caseTicks < 10) return false;
            boolean a = level().getBlockState(new BlockPos(px, py - 1, pz + 2)).is(Blocks.NETHERRACK);
            boolean b = level().getBlockState(new BlockPos(px + 2, py - 1, pz + 1)).is(Blocks.NETHERRACK);
            if (!(a && b) && caseTicks < 50) return false;
            log(f("liquidfiller: filled %s %s", a, b));
            return a && b;
        }, () -> { disable(dev.prismglass.module.world.LiquidFiller.class); clearArea(); });

        add("MiddleClick: XP bottles while held, rocket while gliding", 40, () -> {
            cmd("give @p experience_bottle 16");
            var m = enable(dev.prismglass.module.player.MiddleClick.class);
            m.air.parse("XP");
        }, () -> {
            if (caseTicks < 8) return false;
            var m = dev.prismglass.Prism.modules().get(dev.prismglass.module.player.MiddleClick.class);
            try {
                if (caseTicks == 8) { var c = m.getClass().getDeclaredMethod("click"); c.setAccessible(true); c.invoke(m); }
                if (caseTicks > 8 && caseTicks < 14) {
                    var h = m.getClass().getDeclaredField("held"); h.setAccessible(true); h.setInt(m, h.getInt(m) + 1);
                    var hold = m.getClass().getDeclaredMethod("hold"); hold.setAccessible(true); hold.invoke(m);
                }
            } catch (Exception e) { log("middleclick reflect " + e); return false; }
            if (caseTicks < 24) return false;
            int left = mc.player.getInventory().countItem(Items.EXPERIENCE_BOTTLE);
            log(f("middleclick: %d bottles left, selected %d", left, mc.player.getInventory().getSelectedSlot()));
            return left <= 13 && left >= 8;
        }, () -> {
            dev.prismglass.Prism.modules().get(dev.prismglass.module.player.MiddleClick.class).air.parse("Pearl");
            disable(dev.prismglass.module.player.MiddleClick.class);
            cmd("kill @e[type=experience_bottle]"); cmd("kill @e[type=experience_orb]");
        });

        add("SpearPhase: a Lunge spear dash throws you forward", 80, () -> {
            clearArea();
            cmd(f("fill %d %d %d %d %d %d air", px - 2, py, pz + 9, px + 2, py + 3, pz + 20));
            cmd("item replace entity @p hotbar.3 with iron_spear[enchantments={lunge:3}]");
            cmd("item replace entity @p hotbar.0 with stone");
            cmd("effect give @p saturation 5 10 true");
        }, () -> {
            if (caseTicks == 6) { mc.player.getInventory().setSelectedSlot(0); mc.player.setYRot(0); mc.player.setXRot(0); }
            if (caseTicks == 8) enable(dev.prismglass.module.combat.SpearPhase.class);
            if (caseTicks < 50) return false;
            var sp = dev.prismglass.Prism.modules().get(dev.prismglass.module.combat.SpearPhase.class);
            double moved = splayer().getZ() - (pz + 0.5);
            int sel = mc.player.getInventory().getSelectedSlot();
            log(f("spearphase: dashes=%d moved %.2f, slot back=%s", sp.dashes, moved, sel == 0));
            return sp.dashes == 1 && moved > 3 && sel == 0;
        }, () -> { disable(dev.prismglass.module.combat.SpearPhase.class); });

        add("AutoCraft: gold ingots into a block at a crafting table", 60, () -> {
            clearArea();
            cmd(f("setblock %d %d %d crafting_table", px, py, pz + 2));
            cmd("recipe give @p *");
            cmd("give @p gold_ingot 9");
            var ac = enable(dev.prismglass.module.player.AutoCraft.class);
            ac.items.parse("gold_block");
        }, () -> {
            if (caseTicks == 8) open(new BlockPos(px, py, pz + 2));
            if (caseTicks < 30) return false;
            int blocks = splayer().getInventory().countItem(Items.GOLD_BLOCK), ingots = splayer().getInventory().countItem(Items.GOLD_INGOT);
            log(f("autocraft: blocks=%d ingots=%d", blocks, ingots));
            return blocks == 1 && ingots == 0;
        }, () -> { mc.player.closeContainer(); disable(dev.prismglass.module.player.AutoCraft.class); clearArea(); });

        add("AutoBedCrafter: opens the table, crafts 2 beds, closes it", 120, () -> {
            clearArea();
            cmd(f("setblock %d %d %d crafting_table", px + 1, py, pz + 2));
            cmd("recipe give @p *");
            cmd("give @p white_wool 6");
            cmd("give @p oak_planks 6");
            var bc = enable(dev.prismglass.module.combat.AutoBedCrafter.class);
            bc.refill.parse("2"); bc.target.parse("8");
        }, () -> {
            if (caseTicks < 20) return false;
            int beds = splayer().getInventory().countItem(Items.WHITE_BED);
            if ((beds < 2 || mc.screen != null) && caseTicks < 110) return false;
            log(f("autobedcrafter: beds=%d wool=%d screen=%s", beds, splayer().getInventory().countItem(Items.WHITE_WOOL), mc.screen));
            return beds == 2 && mc.screen == null;
        }, () -> { mc.player.closeContainer(); disable(dev.prismglass.module.combat.AutoBedCrafter.class); clearArea(); });

        add("AutoSmelter: loads ore + fuel, takes the result", 60, () -> {
            clearArea();
            cmd(f("setblock %d %d %d furnace", px, py, pz + 2));
            cmd(f("item replace block %d %d %d container.2 with iron_ingot 3", px, py, pz + 2));
            cmd("give @p raw_iron 4");
            cmd("give @p coal 2");
            cmd("give @p stick 3");
            enable(dev.prismglass.module.world.AutoSmelter.class);
        }, () -> {
            if (caseTicks == 8) open(new BlockPos(px, py, pz + 2));
            if (caseTicks < 30) return false;
            String slots = server().submit(() -> level().getBlockEntity(new BlockPos(px, py, pz + 2)) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity fb
                ? fb.getItem(0) + "|" + fb.getItem(1) + "|" + fb.getItem(2) : "none").join();
            int ingots = splayer().getInventory().countItem(Items.IRON_INGOT);
            log("autosmelter: furnace " + slots + " ingots taken=" + ingots);
            return slots.contains("raw_iron") && slots.contains("coal") && !slots.contains("stick") && ingots == 3;
        }, () -> { mc.player.closeContainer(); disable(dev.prismglass.module.world.AutoSmelter.class); clearArea(); });

        for (boolean awkward : new boolean[]{false, true}) {
            add(awkward ? "AutoBrewer: awkward bottles get blaze powder next" : "AutoBrewer: water in, fuel in, nether wart first", 60, () -> {
                clearArea();
                cmd(f("setblock %d %d %d brewing_stand", px, py, pz + 2));
                if (awkward) for (int i = 0; i < 3; i++)
                    cmd(f("item replace block %d %d %d container.%d with potion[potion_contents={potion:\"minecraft:awkward\"}]", px, py, pz + 2, i));
                else cmd("give @p potion[potion_contents={potion:\"minecraft:water\"}] 3");
                cmd("give @p nether_wart 4");
                cmd("give @p blaze_powder 4");
                enable(dev.prismglass.module.world.AutoBrewer.class).potion.parse("Strength");
            }, () -> {
                if (caseTicks == 8) open(new BlockPos(px, py, pz + 2));
                if (caseTicks < 34) return false;
                String slots = server().submit(() -> {
                    if (!(level().getBlockEntity(new BlockPos(px, py, pz + 2)) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity b)) return "none";
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 5; i++) sb.append(b.getItem(i).getItem()).append(' ').append(b.getItem(i).get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)).append(" | ");
                    return sb.toString();
                }).join();
                log("autobrewer: " + slots);
                String[] s = slots.split(" \\| ");
                boolean bottles = s.length >= 5 && s[0].startsWith("minecraft:potion") && s[1].startsWith("minecraft:potion") && s[2].startsWith("minecraft:potion");
                boolean ingredient = s.length >= 5 && s[3].startsWith(awkward ? "minecraft:blaze_powder" : "minecraft:nether_wart");
                boolean fuelIn = s.length >= 5 && (s[4].startsWith("minecraft:blaze_powder") || (awkward ? splayer().getInventory().countItem(Items.BLAZE_POWDER) == 2 : splayer().getInventory().countItem(Items.BLAZE_POWDER) == 3));
                return bottles && ingredient && fuelIn;
            }, () -> { mc.player.closeContainer(); disable(dev.prismglass.module.world.AutoBrewer.class); clearArea(); });
        }

        for (String out : new String[]{"ThrowUp", "ToChest"}) {
            add("SpamDupe " + out + ": runs the given number of cycles", 400, () -> {
                clearArea();
                cmd("kill @e[type=item]");
                if (out.equals("ToChest")) // four single chests (gaps so they don't join), 27 stacks each
                    for (int k = 0; k < 4; k++) cmd(f("setblock %d %d %d chest", px - 3 + 2 * k, py, pz + 2));
                cmd("item replace entity @p hotbar.0 with diamond 3");
                cmd("item replace entity @p hotbar.4 with stick 5");
            }, () -> {
                var spam = dev.prismglass.Prism.modules().get(dev.prismglass.module.anarchy.SpamDupe.class);
                if (caseTicks == 6) {
                    mc.player.getInventory().setSelectedSlot(0);
                    spam.command.parse("give @s diamond {n}");
                    spam.amount.parse("Items");
                    spam.maxN.parse("2304");
                    spam.output.parse(out);
                    spam.start(2);
                }
                if (caseTicks % 40 == 0 && spam.isEnabled()) log("spamdupe " + spam.debug());
                if (caseTicks < 10 || spam.isEnabled()) return false;
                int thrown = 0;
                // block entities have to be read on the server thread
                int inChests = server().submit(() -> {
                    int n = 0;
                    for (int k = 0; k < 4; k++)
                        if (level().getBlockEntity(new BlockPos(px - 3 + 2 * k, py, pz + 2)) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c)
                            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.DIAMOND)) n += c.getItem(i).getCount();
                    return n;
                }).join();
                for (var e : level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, splayer().getBoundingBox().inflate(32)))
                    if (e.getItem().is(Items.DIAMOND)) thrown += e.getItem().getCount();
                int held = splayer().getInventory().getItem(0).getCount(), sticks = splayer().getInventory().countItem(Items.STICK);
                log(f("spamdupe %s: cycles=%d inChests=%d thrown=%d held=%d sticks=%d screen=%s", out, spam.cycles(), inChests, thrown, held, sticks, mc.screen));
                int moved = out.equals("ToChest") ? inChests : thrown;
                int other = out.equals("ToChest") ? thrown : inChests;
                return spam.cycles() == 2 && moved > 4000 && other == 0 && held == 1 && sticks == 5 && mc.screen == null;
            }, () -> {
                var spam = dev.prismglass.Prism.modules().get(dev.prismglass.module.anarchy.SpamDupe.class);
                spam.setEnabled(false);
                spam.output.parse("ThrowUp");
                cmd("kill @e[type=item]");
                clearArea();
            });
        }

        for (boolean on : new boolean[]{true, false}) {
            add(on ? "NoRotate: a server tp does not turn the camera" : "Server tp turns the camera without NoRotate (control)", 30, () -> {
                if (on) enable(dev.prismglass.module.player.NoRotate.class); else disable(dev.prismglass.module.player.NoRotate.class);
            }, () -> {
                if (caseTicks == 6) { mc.player.setYRot(0); mc.player.setXRot(0); cmd(f("tp @p %.1f %d %.1f 90 45", (px) + 0.5, py, (pz) + 0.5)); }
                if (caseTicks < 20) return false;
                log(f("norotate %s: yaw %.1f pitch %.1f", on, mc.player.getYRot(), mc.player.getXRot()));
                boolean kept = Math.abs(mc.player.getYRot()) < 1 && Math.abs(mc.player.getXRot()) < 1;
                return on == kept;
            }, () -> disable(dev.prismglass.module.player.NoRotate.class));
        }
    }
}

package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.anarchy.ItemFrameDupe;
import dev.prismglass.module.anarchy.ShulkerDupe;
import dev.prismglass.module.player.XCarry;
import dev.prismglass.module.world.BookBot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Dev only (-Pdupetest): BookBot (books written and signed server-side), XCarry (grid kept after close, returned on
 * disable), ItemFrameDupe (place/punch loop runs; vanilla must not duplicate) and ShulkerDupe (attempt fires; vanilla
 * must reject the clicks). Flat survival world, logs "[dupetest]" lines and exits.
 */
public final class DupeTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t;
    private static boolean requested;
    private static BlockPos base, shulker, top, upStart;
    private static int downAt, endAt;
    private static boolean downLogged, spamLogged;

    private DupeTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[dupetest] {}", s); }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void onServer(MinecraftServer s, java.util.function.Consumer<ServerPlayer> c) {
        s.execute(() -> c.accept(s.getPlayerList().getPlayers().get(0)));
    }

    /** Diamonds the player has, lying around, in item frames and inside dropped shulker boxes. */
    private static int diamonds(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(Items.DIAMOND)) n += st.getCount();
            n += inBox(st);
        }
        for (ItemEntity e : p.level().getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(16))) {
            if (e.getItem().is(Items.DIAMOND)) n += e.getItem().getCount();
            n += inBox(e.getItem());
        }
        for (net.minecraft.world.entity.decoration.ItemFrame f : p.level().getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(p.blockPosition()).inflate(16)))
            if (f.getItem().is(Items.DIAMOND)) n += f.getItem().getCount();
        return n;
    }

    private static int inBox(ItemStack st) {
        ItemContainerContents c = st.get(DataComponents.CONTAINER);
        if (c == null) return 0;
        int n = 0;
        for (ItemStack in : c.nonEmptyItemCopyStream().toList()) if (in.is(Items.DIAMOND)) n += in.getCount();
        return n;
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismdupe-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        BookBot book = Prism.modules().get(BookBot.class);
        XCarry xcarry = Prism.modules().get(XCarry.class);
        ItemFrameDupe frames = Prism.modules().get(ItemFrameDupe.class);
        ShulkerDupe shulk = Prism.modules().get(ShulkerDupe.class);

        if (t == 30) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            Prism.anticheat().preset.parse("Custom");
            Prism.anticheat().update();
            run(s, "gamerule advance_time false");
            run(s, "clear @p");
            run(s, "item replace entity @p hotbar.1 with writable_book");
            run(s, "item replace entity @p hotbar.3 with writable_book");
            run(s, "item replace entity @p inventory.5 with writable_book");
            run(s, "item replace entity @p inventory.9 with writable_book");
        }
        if (t == 40) {
            book.mode.parse("Word");
            book.text.parse("prism");
            book.pages.parse("100");
            book.pageChars.parse("1024");
            book.sign.parse("true");
            book.title.parse("Glass");
            book.delay.parse("2");
            book.count.parse("0");
            book.setEnabled(true);
        }
        if (t == 120) {
            log("BookBot still on: " + book.isEnabled() + ", wrote " + book.getInfo());
            onServer(s, p -> {
                int written = 0, writable = 0;
                String sample = "";
                for (int i = 0; i < 36; i++) {
                    ItemStack st = p.getInventory().getItem(i);
                    if (st.is(Items.WRITABLE_BOOK)) writable++;
                    if (st.is(Items.WRITTEN_BOOK)) {
                        written++;
                        WrittenBookContent c = st.get(DataComponents.WRITTEN_BOOK_CONTENT);
                        if (sample.isEmpty() && c != null) {
                            String page = c.pages().get(0).raw().getString();
                            sample = "title='" + c.title().raw() + "' pages=" + c.pages().size() + " page0len=" + page.length() + " page0='" + page.substring(0, Math.min(24, page.length())) + "...'";
                        }
                    }
                }
                log("BOOK written=" + written + " leftWritable=" + writable + " " + sample + (written == 4 && writable == 0 ? " PASS" : " FAIL"));
            });
        }

        // ---- XCarry: two diamonds stacks in the 2x2 grid survive the close packet ----
        if (t == 130) {
            run(s, "clear @p");
            run(s, "item replace entity @p inventory.0 with diamond 7");
            run(s, "item replace entity @p inventory.1 with diamond 5");
        }
        if (t == 140) {
            xcarry.setEnabled(true);
            int id = mc.player.inventoryMenu.containerId;
            // inventory.0 = screen slot 9, crafting inputs are screen slots 1-4
            mc.gameMode.handleContainerInput(id, 9, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(id, 1, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(id, 10, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(id, 4, 0, ContainerInput.PICKUP, mc.player);
            mc.player.closeContainer();
        }
        if (t == 150) onServer(s, p -> {
            int grid = 0;
            for (int i = 1; i <= 4; i++) grid += p.inventoryMenu.getSlot(i).getItem().getCount();
            log("XCARRY after close: grid=" + grid + " (want 12) " + (grid == 12 ? "PASS" : "FAIL"));
        });
        if (t == 155) xcarry.setEnabled(false);
        if (t == 165) onServer(s, p -> {
            int grid = 0;
            for (int i = 1; i <= 4; i++) grid += p.inventoryMenu.getSlot(i).getItem().getCount();
            int inv = p.getInventory().countItem(Items.DIAMOND);
            log("XCARRY after disable: grid=" + grid + " inventory=" + inv + " (want 0/12) " + (grid == 0 && inv == 12 ? "PASS" : "FAIL"));
        });

        // ---- ItemFrameDupe: frame on a wall next to the player, 12 diamonds in hand ----
        if (t == 175) {
            base = mc.player.blockPosition();
            run(s, "clear @p");
            run(s, "tp @p " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " -90 20");
            run(s, "setblock " + (base.getX() + 2) + " " + (base.getY() + 1) + " " + base.getZ() + " stone");
            run(s, "summon item_frame " + (base.getX() + 1) + " " + (base.getY() + 1) + " " + base.getZ() + " {Facing:4b}");
            run(s, "item replace entity @p hotbar.0 with diamond 12");
        }
        if (t == 185) {
            mc.player.getInventory().setSelectedSlot(0);
            frames.rotations.parse("1");
            frames.delay.parse("2");
            frames.setEnabled(true);
            log("ItemFrameDupe on: " + frames.isEnabled());
        }
        if (t == 285) {
            log("FRAME cycles=" + frames.getInfo());
            frames.setEnabled(false);
        }
        if (t == 300) onServer(s, p -> {
            int n = diamonds(p);
            log("FRAME diamonds total=" + n + " (vanilla: 12, no dupe) cycles=" + frames.getInfo() + " " + (n == 12 && !"0".equals(frames.getInfo()) ? "PASS" : "FAIL"));
        });

        // ---- ShulkerDupe: open a shulker with 10 diamonds, the module breaks it and shift-clicks ----
        if (t == 310) {
            shulker = base.offset(-2, 0, 0);
            run(s, "kill @e[type=item]");
            run(s, "kill @e[type=item_frame]");
            run(s, "clear @p");
            run(s, "tp @p " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " 90 30");
            run(s, "item replace entity @p hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]");
            run(s, "setblock " + shulker.getX() + " " + shulker.getY() + " " + shulker.getZ() + " shulker_box{Items:[{Slot:0b,id:\"minecraft:diamond\",count:10}]}");
        }
        if (t == 320) {
            mc.player.getInventory().setSelectedSlot(0);
            shulk.setEnabled(true);
            Vec3 hit = Vec3.atCenterOf(shulker).add(0.5, 0, 0);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.EAST, shulker, false));
        }
        if (t == 345) log("SHULKER module still on: " + shulk.isEnabled() + " attempts=" + shulk.getInfo() + " block=" + mc.level.getBlockState(shulker).getBlock());
        if (t == 360) onServer(s, p -> {
            int n = diamonds(p);
            log("SHULKER diamonds total=" + n + " (vanilla: 10, no dupe) " + (n == 10 && !shulk.isEnabled() ? "PASS" : "FAIL"));
        });
        // ---- StorageHover: double chest with items, unknown -> open once -> remembered ----
        dev.prismglass.module.render.StorageHover hover = Prism.modules().get(dev.prismglass.module.render.StorageHover.class);
        if (t == 380) {
            log("FORMAT shorten(999)=" + dev.prismglass.module.render.StorageHover.shorten(999) + " shorten(2048)=" + dev.prismglass.module.render.StorageHover.shorten(2048)
                + " shorten(16384)=" + dev.prismglass.module.render.StorageHover.shorten(16384) + " shorten(1500000)=" + dev.prismglass.module.render.StorageHover.shorten(1500000)
                + " shorten(3200000000000)=" + dev.prismglass.module.render.StorageHover.shorten(3200000000000L));
            log("FORMAT sizeText(2048)=" + dev.prismglass.module.render.StorageHover.sizeText(2048));
            shulker = base.offset(0, 0, 2);
            String items = "{Items:[{Slot:0b,id:\"minecraft:diamond\",count:64},{Slot:1b,id:\"minecraft:diamond\",count:64},{Slot:5b,id:\"minecraft:golden_apple\",count:12},{Slot:30b,id:\"minecraft:netherite_sword\",count:1,components:{\"minecraft:enchantments\":{sharpness:5}}}]}";
            run(s, "kill @e[type=item]");
            run(s, "clear @p");
            run(s, "setblock " + shulker.getX() + " " + shulker.getY() + " " + shulker.getZ() + " chest[facing=north,type=left]" + items);
            run(s, "setblock " + (shulker.getX() + 1) + " " + shulker.getY() + " " + shulker.getZ() + " chest[facing=north,type=right]");
            run(s, "tp @p " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " 0 22");
            hover.setEnabled(true);
        }
        if (t == 395) log("STORAGE before opening: " + (hover.knows(shulker) ? "contents known (singleplayer read) PASS" : "unknown FAIL"));
        if (t == 395) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-storagehover-unknown.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
        if (t == 400) {
            Vec3 hit = Vec3.atCenterOf(shulker).add(0, 0, -0.5);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.NORTH, shulker, false));
        }
        if (t == 412) log("STORAGE screen=" + (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName()));
        if (t == 415 && mc.gui.screen() != null) mc.player.closeContainer();
        if (t == 425) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-storagehover.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
        // ---- Staircase: 8 steps up east in the air (stone in the way to dig), then 8 steps down east off the top ----
        dev.prismglass.module.world.Staircase stairs = Prism.modules().get(dev.prismglass.module.world.Staircase.class);
        if (t == 435) {
            hover.setEnabled(false);
            base = base.offset(0, 0, 8);
            run(s, "clear @p");
            run(s, "tp @p " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " -90 10");
            run(s, "item replace entity @p hotbar.0 with cobblestone 64");
            run(s, "item replace entity @p hotbar.1 with cobblestone 64");
            run(s, "item replace entity @p hotbar.2 with netherite_pickaxe[enchantments={efficiency:5}]");
            // a stone ceiling over steps 4 and 5 that has to be dug out
            run(s, "fill " + (base.getX() + 4) + " " + (base.getY() + 5) + " " + (base.getZ() - 1) + " " + (base.getX() + 5) + " " + (base.getY() + 6) + " " + (base.getZ() + 1) + " stone");
        }
        if (t == 445) {
            mc.player.getInventory().setSelectedSlot(0);
            stairs.direction.parse("Up");
            stairs.length.parse("8");
            stairs.material.parse("Cobblestone");
            stairs.setEnabled(true);
            upStart = mc.player.blockPosition();
        }
        if (t > 445 && t < 1200 && t % 100 == 0) log("STAIRS up t=" + t + " info=" + stairs.getInfo() + " pos=" + mc.player.blockPosition().toShortString() + " on=" + stairs.isEnabled());
        if (t == 1200 || (t > 450 && t < 1200 && !stairs.isEnabled() && downAt == 0)) {
            BlockPos at = mc.player.blockPosition();
            int ok = 0;
            for (int k = 1; k <= 8; k++) if (!mc.level.getBlockState(upStart.below().relative(Direction.EAST, k).above(k)).isAir()) ok++;
            log("STAIRS UP steps=" + ok + "/8 player=" + at.toShortString() + " start=" + upStart.toShortString() + " dy=" + (at.getY() - upStart.getY()) + " (want 8/8, dy 8) " + (ok == 8 && at.getY() - upStart.getY() == 8 ? "PASS" : "FAIL"));
            stairs.setEnabled(false);
            downAt = t + 10;
        }
        if (downAt > 0 && t == downAt - 5) {
            // a stone hill in front of the top step to dig the down staircase into
            BlockPos at = mc.player.blockPosition();
            run(s, "fill " + (at.getX() + 1) + " " + (at.getY() - 10) + " " + (at.getZ() - 2) + " " + (at.getX() + 12) + " " + (at.getY() - 1) + " " + (at.getZ() + 2) + " stone");
            run(s, "fill " + (at.getX() + 1) + " " + at.getY() + " " + (at.getZ() - 2) + " " + (at.getX() + 12) + " " + (at.getY() + 3) + " " + (at.getZ() + 2) + " air");
        }
        if (downAt > 0 && t == downAt) {
            top = mc.player.blockPosition();
            stairs.direction.parse("Down");
            stairs.length.parse("8");
            stairs.setEnabled(true);
        }
        if (downAt > 0 && t > downAt && t % 100 == 0) log("STAIRS down info=" + stairs.getInfo() + " pos=" + mc.player.blockPosition().toShortString() + " on=" + stairs.isEnabled());
        if (downAt > 0 && (t == downAt + 900 || (t > downAt + 5 && !stairs.isEnabled() && !downLogged))) {
            downLogged = true;
            BlockPos at = mc.player.blockPosition();
            int ok = 0;
            for (int k = 1; k <= 8; k++) if (!mc.level.getBlockState(top.below().relative(Direction.EAST, k).below(k)).isAir()) ok++;
            log("STAIRS DOWN steps=" + ok + "/8 player=" + at.toShortString() + " dy=" + (at.getY() - top.getY()) + " (want 8/8, dy -8) " + (ok == 8 && at.getY() - top.getY() == -8 ? "PASS" : "FAIL"));
            stairs.setEnabled(false);
            endAt = t + 10;
        }
        if (endAt > 0 && t == endAt) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-staircase.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
        // ---- SpamDupe: "give @s diamond {n}" stands in for a server's /dupe ----
        dev.prismglass.module.anarchy.SpamDupe spam = Prism.modules().get(dev.prismglass.module.anarchy.SpamDupe.class);
        if (endAt > 0 && t == endAt + 10) {
            run(s, "kill @e[type=item]");
            run(s, "clear @p");
            run(s, "item replace entity @p hotbar.0 with diamond 3");
            run(s, "item replace entity @p hotbar.4 with stick 5");
        }
        if (endAt > 0 && t == endAt + 20) {
            mc.player.getInventory().setSelectedSlot(0);
            spam.command.parse("give @s diamond {n}");
            spam.amount.parse("Items");
            spam.maxN.parse("2304");
            spam.start(2);
        }
        if (endAt > 0 && t > endAt + 20 && !spam.isEnabled() && !spamLogged) {
            spamLogged = true;
            int items = 0, dia = 0;
            for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(32))) {
                items++;
                if (e.getItem().is(Items.DIAMOND)) dia += e.getItem().getCount();
            }
            int held = mc.player.getInventory().getItem(0).getCount();
            int sticks = mc.player.getInventory().countItem(Items.STICK);
            log("SPAMDUPE on=" + spam.isEnabled() + " cycles=" + spam.getInfo() + " thrownDiamonds=" + dia + " itemEntities=" + items
                + " held=" + held + " sticksKept=" + sticks + (!spam.isEnabled() && held == 1 && sticks == 5 && dia > 2000 ? " PASS" : " FAIL"));
        }
        // ---- PlayerHat: third person from the front, night-ish backdrop like the reference ----
        if (endAt > 0 && t == endAt + 100) {
            run(s, "kill @e[type=item]");
            run(s, "time set 14000");
            run(s, "tp @p ~ ~ ~40");
            var gui = Prism.modules().get(dev.prismglass.module.client.ClickGui.class);
            gui.playerHat.set(true);
            mc.player.setXRot(10);
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        }
        if (endAt > 0 && t == endAt + 112) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-playerhat.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
        if (endAt > 0 && t == endAt + 115) {
            Prism.modules().get(dev.prismglass.module.client.ClickGui.class).playerHat.set(false);
            mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        }
        if (endAt > 0 && t == endAt + 120) {
            log("SPAMDUPE 2.5s after stopping (items picked back up): held=" + mc.player.getInventory().getItem(0).getCount());
            log("END");
            mc.stop();
        }
    }
}

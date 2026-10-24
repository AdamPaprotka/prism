package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.*;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Look at any storage block and a glass card next to the crosshair shows what's known about it.
 *
 * <p>A multiplayer server sends a container's items only when someone opens it (Meteor's StorageESP/EChestMemory work
 * the same way), so there contents come from the last time you opened that block this session. In singleplayer (or a
 * LAN world you host) the block is read straight from the built-in server, no opening needed. Capacity and the "full" size are
 * known for every storage block straight away. Sizes are the NBT data size the way a chest saves its items
 * (the same encoding as Meteor's byte-size tooltip), 1 KiloByte = 1024 Bytes.
 */
public class StorageHover extends Module {
    public final NumberSetting range = num("Range", 6, 3, 64, 0.5, "How far away you can look at a storage block.");
    public final BoolSetting grid = bool("Grid", true, "Item icons of the remembered contents.");
    public final BoolSetting size = bool("Size", true, "Data size of the contents in Bits/Bytes/KiloBytes/MegaBytes/GigaBytes.");
    public final BoolSetting fullSize = bool("FullSize", true, "Also the size it would have with every slot holding 64 stone.");
    public final BoolSetting topItems = bool("TopItems", true, "Most common items in it.");

    /** bytes = the contents' data size, measured once when the snapshot is taken. */
    private record Snapshot(List<ItemStack> items, long time, long bytes) {}

    /** Remembered contents per dimension + position; ender chests share one entry. */
    private static final Map<String, Snapshot> SEEN = new HashMap<>();
    private static final String ECHEST = "echest";
    /** Full-size bytes per capacity (64 stone in every slot). */
    private static final Map<Integer, Long> FULL = new HashMap<>();
    private BlockPos clicked;
    private long clickedAt;

    public StorageHover() { super("StorageHover", "Look at a chest/barrel/shulker to see its size and remembered contents.", Category.RENDER); }

    // ---- remembering contents ------------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketEvent e) {
        if (e.packet instanceof ServerboundUseItemOnPacket p) {
            clicked = p.hitResult().getBlockPos();
            clickedAt = System.currentTimeMillis();
        }
    }

    @Override
    public void onTick() {
        readLocalServer();
        if (clicked == null || System.currentTimeMillis() - clickedAt > 30_000) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> s) || s.getMenu() == mc.player.inventoryMenu) return;
        if (!isStorage(clicked)) { clicked = null; return; }
        int n = s.getMenu().slots.size() - 36; // the menu's own slots come before the player inventory
        if (n <= 0) return;
        List<ItemStack> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) items.add(s.getMenu().getSlot(i).getItem().copy());
        Snapshot snap = new Snapshot(items, System.currentTimeMillis(), dataSize(items));
        for (String key : keys(clicked)) SEEN.put(key, snap);
    }

    @Override public void onWorldJoin() { clicked = null; }

    /**
     * Singleplayer / a LAN world you host: the server runs inside this game, so the block you look at is read
     * straight from it (like Jade/WTHIT do) - no opening needed. Done on the server thread, a few times a second.
     */
    private void readLocalServer() {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player.tickCount % 5 != 0) return;
        HitResult hr = mc.player.pick(range.get(), 1f, false);
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhr.getBlockPos();
        if (!isStorage(pos) || mc.level.getBlockEntity(pos) instanceof EnderChestBlockEntity) return;
        List<String> k = keys(pos);
        var dim = mc.level.dimension();
        boolean dbl = k.size() > 1;
        BlockPos other = dbl ? ChestBlock.getConnectedBlockPos(pos, mc.level.getBlockState(pos)) : null;
        server.execute(() -> {
            var level = server.getLevel(dim);
            if (level == null) return;
            List<ItemStack> items = new ArrayList<>();
            // a double chest's menu lists the half you'd see on top first; order only matters for the grid
            for (BlockPos p : dbl ? orderedHalves(level, pos, other) : List.of(pos)) {
                if (!(level.getBlockEntity(p) instanceof Container c)) return;
                for (int i = 0; i < c.getContainerSize(); i++) items.add(c.getItem(i).copy());
            }
            mc.execute(() -> {
                Snapshot snap = new Snapshot(items, System.currentTimeMillis(), dataSize(items));
                for (String key : k) SEEN.put(key, snap);
            });
        });
    }

    /** Vanilla's double chest menu shows the RIGHT half (chest type) first. */
    private static List<BlockPos> orderedHalves(net.minecraft.server.level.ServerLevel level, BlockPos a, BlockPos b) {
        return level.getBlockState(a).getValue(ChestBlock.TYPE) == ChestType.RIGHT ? List.of(a, b) : List.of(b, a);
    }

    /** Cache keys of a block: both halves of a double chest share one snapshot. */
    private static List<String> keys(BlockPos pos) {
        if (mc.level.getBlockEntity(pos) instanceof EnderChestBlockEntity) return List.of(ECHEST);
        String dim = mc.level.dimension().identifier().toString();
        BlockState st = mc.level.getBlockState(pos);
        List<String> out = new ArrayList<>(2);
        out.add(dim + pos.asLong());
        if (st.getBlock() instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            out.add(dim + ChestBlock.getConnectedBlockPos(pos, st).asLong());
        }
        return out;
    }

    /** Dev tests: are this block's contents known? */
    public boolean knows(BlockPos pos) { return SEEN.containsKey(keys(pos).get(0)); }

    private static boolean isStorage(BlockPos pos) {
        BlockEntity be = mc.level.getBlockEntity(pos);
        return be instanceof Container || be instanceof EnderChestBlockEntity;
    }

    private static int capacity(BlockPos pos) {
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (be instanceof EnderChestBlockEntity) return 27;
        int n = be instanceof Container c ? c.getContainerSize() : 0;
        BlockState st = mc.level.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) n *= 2;
        return n;
    }

    // ---- the card ------------------------------------------------------------------------------------

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (mc.gui.screen() != null || mc.gui.hud.isHidden()) return;
        HitResult hr = mc.player.pick(range.get(), delta, false);
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhr.getBlockPos();
        if (!isStorage(pos)) return;

        int cap = capacity(pos);
        List<String> k = keys(pos);
        Snapshot snap = SEEN.get(k.get(0));
        BlockState st = mc.level.getBlockState(pos);
        boolean dbl = st.getBlock() instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE;

        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        line(lines, colors, st.getBlock().getName().getString() + (dbl ? " (double)" : ""), 0xFFFFFFFF);
        line(lines, colors, "Capacity: " + cap + " slots, " + group(cap * 64L) + " items", 0xFFC8D0DC);
        if (snap == null) {
            line(lines, colors, "Contents: open it once to see them", 0xFF8A93A6);
        } else {
            int used = 0;
            long count = 0;
            Map<Item, Long> totals = new LinkedHashMap<>();
            for (ItemStack s : snap.items) {
                if (s.isEmpty()) continue;
                used++;
                count += s.getCount();
                totals.merge(s.getItem(), (long) s.getCount(), Long::sum);
            }
            line(lines, colors, "Used: " + used + "/" + snap.items.size() + " slots, " + group(count) + " items, " + (snap.items.size() - used) + " free", 0xFFC8D0DC);
            if (topItems.get() && !totals.isEmpty()) {
                totals.entrySet().stream().sorted(Map.Entry.<Item, Long>comparingByValue().reversed()).limit(3)
                    .forEach(en -> line(lines, colors, "  " + group(en.getValue()) + "x " + en.getKey().getDefaultInstance().getHoverName().getString(), 0xFFAEB6C6));
            }
            if (size.get()) line(lines, colors, "Size: " + sizeText(snap.bytes), 0xFF8AB4FF);
            line(lines, colors, "Seen " + ago(snap.time), 0xFF8A93A6);
        }
        if (fullSize.get() && cap > 0) {
            long full = FULL.computeIfAbsent(cap, c -> {
                List<ItemStack> stones = new ArrayList<>(c);
                for (int i = 0; i < c; i++) stones.add(new ItemStack(Items.STONE, 64));
                return dataSize(stones);
            });
            line(lines, colors, "Full size: " + sizeText(full), 0xFF6F86B8);
        }

        int rows = snap != null && grid.get() ? (snap.items.size() + 8) / 9 : 0;
        int cols = snap != null && grid.get() ? Math.min(9, snap.items.size()) : 0;
        int w = cols * 18;
        for (String s : lines) w = Math.max(w, mc.font.width(s));
        w += 10;
        int h = lines.size() * 10 + 8 + (rows > 0 ? rows * 18 + 4 : 0);
        int x = ctx.guiWidth() / 2 + 14, y = Math.max(2, ctx.guiHeight() / 2 - h / 2);
        if (x + w > ctx.guiWidth() - 2) x = Math.max(2, ctx.guiWidth() / 2 - 14 - w);

        Glass.panel(ctx, x, y, w, h, Prism.modules().get(ClickGui.class).panelStyle());
        int ty = y + 5;
        for (int i = 0; i < lines.size(); i++, ty += 10) ctx.text(mc.font, lines.get(i), x + 5, ty, colors.get(i), true);
        if (rows > 0) {
            int gy = ty + 2;
            for (int i = 0; i < snap.items.size(); i++) {
                ItemStack s = snap.items.get(i);
                if (s.isEmpty()) continue;
                int ix = x + 5 + (i % 9) * 18 + 1, iy = gy + (i / 9) * 18 + 1;
                ctx.item(s, ix, iy);
                ctx.itemDecorations(mc.font, s, ix, iy);
            }
        }
    }

    private static void line(List<String> l, List<Integer> c, String s, int color) { l.add(s); c.add(color); }

    // ---- sizes ---------------------------------------------------------------------------------------

    /** Bytes of the NBT a container saves for these items: {Items:[{Slot:b, id, count, components}...]}. */
    public static long dataSize(List<ItemStack> items) {
        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ListTag list = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            Tag t = ItemStack.CODEC.encodeStart(ops, s).result().orElse(null);
            if (!(t instanceof CompoundTag c)) continue;
            c.putByte("Slot", (byte) i);
            list.add(c);
        }
        CompoundTag root = new CompoundTag();
        root.put("Items", list);
        long[] n = {0};
        try (DataOutputStream out = new DataOutputStream(new OutputStream() {
            @Override public void write(int b) { n[0]++; }
            @Override public void write(byte[] b, int off, int len) { n[0] += len; }
        })) {
            NbtIo.write(root, out);
        } catch (IOException ignored) {}
        return n[0];
    }

    /** "16.4K Bits, 2.05K Bytes, 2 KiloBytes, 0.00195 MegaBytes, 0.00000191 GigaBytes" */
    public static String sizeText(long bytes) {
        return shorten(bytes * 8) + " Bits, " + shorten(bytes) + " Bytes, " + plain(bytes / 1024.0) + " KiloBytes, "
            + plain(bytes / 1048576.0) + " MegaBytes, " + plain(bytes / 1073741824.0) + " GigaBytes";
    }

    private static final String[] UNITS = {"", "K", "M", "B", "T"};

    /** 999 -> "999", 2048 -> "2.05K", 16384 -> "16.4K", 1500000 -> "1.5M" (3 significant digits). */
    public static String shorten(long n) {
        int u = 0;
        double v = n;
        while (Math.abs(v) >= 1000 && u < UNITS.length - 1) { v /= 1000; u++; }
        if (u == 0) return String.valueOf(n);
        return plain(v) + UNITS[u];
    }

    /** 3 significant digits, never scientific notation: 0.00000191, 2, 16.4. */
    public static String plain(double v) {
        if (v == 0) return "0";
        return new BigDecimal(v).round(new MathContext(3)).stripTrailingZeros().toPlainString();
    }

    private static String group(long n) { return String.format(Locale.ROOT, "%,d", n); }

    private static String ago(long time) {
        long s = (System.currentTimeMillis() - time) / 1000;
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h " + (s % 3600 / 60) + "m ago";
    }
}

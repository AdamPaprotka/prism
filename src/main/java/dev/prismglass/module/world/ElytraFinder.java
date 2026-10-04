package dev.prismglass.module.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.module.render.Xray;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import dev.prismglass.xray.StructurePredictor;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Elytras only generate on End ships (an item frame on the ship's deck). Finds them two ways:
 * <ul>
 *   <li><b>Seed</b>: builds every nearby end city with vanilla's own generator (seed from {@code .seed}) and reads the
 *       ship's "Elytra" marker: exact elytra frame coordinates for ships you've never loaded.</li>
 *   <li><b>Loaded</b>: item frames are entities the server sends with their item, so an elytra frame is seen exactly;
 *       ships are recognised by the dragon head on the bow. A ship whose frame is empty or gone is marked looted.</li>
 * </ul>
 */
public class ElytraFinder extends Module {
    public final ModeSetting mode = mode("Mode", "Both", "Seed = predicted from the world seed (.seed). Loaded = seen in loaded chunks.", "Both", "Seed", "Loaded");
    public final NumberSetting radius = num("Radius", 64, 8, 256, 8, "Seed mode: chunks around you to search for end cities.");
    public final NumberSetting renderDistance = num("RenderDistance", 6000, 100, 30000, 100, "Hide markers farther than this (blocks).");
    public final BoolSetting showLooted = bool("ShowLooted", true, "Also mark ships whose elytra is already taken.");
    public final BoolSetting labels = bool("Labels", true, "Name and distance on screen.");
    public final BoolSetting tracers = bool("Tracers", true, "Lines to elytras.");
    public final BoolSetting notify = bool("Notify", true, "Message when an elytra or a ship is found.");

    private enum Kind { ELYTRA, SHIP, LOOTED }

    private record Marker(Kind kind, BlockPos pos, boolean predicted) {}
    private record Label(String text, int color, double x, double y) {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Prism ElytraFinder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile List<StructurePredictor.Ship> predictedShips = List.of();
    private volatile boolean busy;
    private StructurePredictor predictor;
    private String scanKey = "";
    private ChunkPos scanCenter;

    private final List<BlockPos> elytraFrames = new ArrayList<>();   // seen this tick
    private final Map<BlockPos, Long> lootedSince = new HashMap<>(); // predicted elytra spot loaded, frame missing/empty
    private final Set<BlockPos> lootedConfirmed = new HashSet<>();
    private final Map<Long, BlockPos> shipHeads = new HashMap<>();   // dragon heads by chunk
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Set<Long> scanned = new HashSet<>();
    private final List<Marker> announced = new ArrayList<>();
    private final List<Label> labelList = new ArrayList<>();
    private int ticks;

    public ElytraFinder() { super("ElytraFinder", "Finds elytras and End ships (from the seed, or seen in loaded chunks).", Category.WORLD); }

    @Override
    public void onEnable() {
        predictedShips = List.of();
        scanKey = "";
        elytraFrames.clear();
        lootedSince.clear();
        lootedConfirmed.clear();
        shipHeads.clear();
        queue.clear();
        scanned.clear();
    }

    @Override
    public void onWorldJoin() {
        onEnable();
        announced.clear();
        predictor = null;
    }

    private boolean inEnd() { return mc.level != null && mc.level.dimension() == Level.END; }

    // ---- tick ------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (!inEnd()) return;
        ticks++;
        if (!mode.is("Loaded")) tickSeed();
        if (!mode.is("Seed")) tickLoaded();
    }

    private void tickSeed() {
        String seedText = Prism.modules().get(Xray.class).seed.get().trim();
        if (seedText.isEmpty() || busy) return;
        long seed = Xray.parseSeed(seedText);
        ChunkPos here = mc.player.chunkPosition();
        int r = radius.getInt();
        String key = seed + "|" + r;
        boolean moved = scanCenter == null || Math.max(Math.abs(here.x() - scanCenter.x()), Math.abs(here.z() - scanCenter.z())) > r / 4;
        if (key.equals(scanKey) && !moved) return;
        scanKey = key;
        scanCenter = here;
        busy = true;
        WORKER.execute(() -> {
            try {
                if (predictor == null || !predictor.isFor(Level.END, seed)) predictor = StructurePredictor.create(Level.END, seed);
                predictedShips = predictor == null ? List.of() : predictor.endShips(here.x(), here.z(), r);
            } catch (Throwable t) {
                Prism.LOG.error("[elytra] prediction failed", t);
            } finally {
                busy = false;
            }
        });
    }

    private void tickLoaded() {
        // item frames are entities with their item synced: an elytra frame is exact
        elytraFrames.clear();
        List<ItemFrame> frames = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemFrame f) {
                frames.add(f);
                if (f.getItem().is(Items.ELYTRA)) elytraFrames.add(f.blockPosition());
            }
        }
        for (BlockPos p : elytraFrames) announce(new Marker(Kind.ELYTRA, p, false));

        // predicted elytra spots that are loaded but hold no elytra: looted (give entity data a few seconds to arrive)
        for (StructurePredictor.Ship s : predictedShips) {
            if (s.elytra() == null || lootedConfirmed.contains(s.elytra())) continue;
            // servers only send item frames within ~10 chunks (less with entity-range tweaks): judge only up close
            double dx = mc.player.getX() - s.elytra().getX(), dz = mc.player.getZ() - s.elytra().getZ();
            boolean close = dx * dx + dz * dz < 96 * 96;
            if (!close || mc.level.getChunkSource().getChunkNow(s.elytra().getX() >> 4, s.elytra().getZ() >> 4) == null
                || near(elytraFrames, s.elytra(), 2)) {
                lootedSince.remove(s.elytra());
                continue;
            }
            long since = lootedSince.computeIfAbsent(s.elytra(), k -> (long) ticks);
            if (ticks - since > 60) {
                lootedConfirmed.add(s.elytra());
                announce(new Marker(Kind.LOOTED, s.elytra(), false));
            }
        }

        if (ticks % 2 == 0) scanChunks();
    }

    /** Dragon heads (ship bow), budgeted like Xray. */
    private void scanChunks() {
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        int r = Math.max(2, mc.options.getEffectiveRenderDistance());
        if (queue.isEmpty()) {
            for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
                long key = ChunkPos.pack(x, z);
                if (!scanned.contains(key)) queue.add(key);
            }
        }
        long deadline = System.nanoTime() + 1_000_000;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            long key = queue.poll();
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            scanned.add(key);
            BlockPos head = findDragonHead(chunk);
            if (head != null) {
                shipHeads.put(key, head);
                announce(new Marker(Kind.SHIP, head, false));
            }
        }
        scanned.removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - cx), Math.abs(ChunkPos.getZ(k) - cz)) > r + 8);
    }

    private static BlockPos findDragonHead(LevelChunk chunk) {
        int ox = chunk.getPos().x() << 4, oz = chunk.getPos().z() << 4;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> st.is(Blocks.DRAGON_WALL_HEAD) || st.is(Blocks.DRAGON_HEAD))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                var st = s.getBlockState(bx, by, bz);
                if (st.is(Blocks.DRAGON_WALL_HEAD) || st.is(Blocks.DRAGON_HEAD)) return new BlockPos(ox + bx, baseY + by, oz + bz);
            }
        }
        return null;
    }

    private static boolean near(Collection<BlockPos> list, BlockPos p, double dist) {
        for (BlockPos o : list) if (o.distSqr(p) <= dist * dist) return true;
        return false;
    }

    private void announce(Marker m) {
        if (!notify.get()) return;
        for (Marker o : announced) if (o.kind() == m.kind() && o.pos().distSqr(m.pos()) < 64 * 64) return;
        if (m.kind() == Kind.SHIP && near(elytraFrames, m.pos(), 40)) return; // the elytra message says it all
        announced.add(m);
        long dist = Math.round(Math.sqrt(mc.player.blockPosition().distSqr(m.pos())));
        String where = m.pos().getX() + ", " + m.pos().getY() + ", " + m.pos().getZ() + " (" + dist + "m)";
        switch (m.kind()) {
            case ELYTRA -> ChatUtil.good("Elytra at " + where);
            case SHIP -> ChatUtil.info("End ship at " + where);
            case LOOTED -> ChatUtil.info("End ship at " + where + " is already looted");
        }
    }

    // ---- markers / render -------------------------------------------------------------------------------------

    private List<Marker> markers() {
        List<Marker> out = new ArrayList<>();
        for (BlockPos p : elytraFrames) out.add(new Marker(Kind.ELYTRA, p, false));
        for (StructurePredictor.Ship s : predictedShips) {
            if (s.elytra() != null) {
                if (near(elytraFrames, s.elytra(), 2)) continue; // seen for real
                if (lootedConfirmed.contains(s.elytra())) {
                    if (showLooted.get()) out.add(new Marker(Kind.LOOTED, s.elytra(), false));
                } else out.add(new Marker(Kind.ELYTRA, s.elytra(), true));
            } else out.add(new Marker(Kind.SHIP, s.ship(), true));
        }
        for (BlockPos head : shipHeads.values()) {
            boolean covered = false;
            for (Marker m : out) if (m.pos().distSqr(head) < 40 * 40) { covered = true; break; }
            if (!covered) out.add(new Marker(Kind.SHIP, head, false));
        }
        return out;
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        labelList.clear();
        if (!inEnd()) return;
        double maxSq = renderDistance.get() * renderDistance.get();
        Vec3 eye = mc.player.getEyePosition(delta);
        for (Marker m : markers()) {
            Vec3 c = Vec3.atBottomCenterOf(m.pos());
            double dSq = eye.distanceToSqr(c);
            if (dSq > maxSq) continue;
            int col = switch (m.kind()) { case ELYTRA -> 0xFFB98CFF; case SHIP -> 0xFFE7A6FF; case LOOTED -> 0xFF8A8A8A; };
            AABB beam = new AABB(c.x - 0.15, m.pos().getY(), c.z - 0.15, c.x + 0.15, m.pos().getY() + 64, c.z + 0.15);
            Render3D.box(beam, m.predicted() ? 0 : ColorUtil.withAlpha(col, 60), ColorUtil.withAlpha(col, m.predicted() ? 150 : 230), true);
            Render3D.box(new AABB(m.pos()), ColorUtil.withAlpha(col, m.predicted() ? 25 : 70), ColorUtil.withAlpha(col, 220), true);
            if (tracers.get() && m.kind() == Kind.ELYTRA) Render3D.tracer(c.add(0, 0.5, 0), ColorUtil.withAlpha(col, 150));
            if (labels.get()) {
                double[] s = Render3D.project(c.add(0, 1.8, 0));
                if (s != null) {
                    String name = switch (m.kind()) { case ELYTRA -> "Elytra"; case SHIP -> "End ship"; case LOOTED -> "Looted ship"; };
                    labelList.add(new Label(name + (m.predicted() ? " §7(seed)" : "") + " §7" + Math.round(Math.sqrt(dSq)) + "m", col, s[0], s[1]));
                }
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (labelList.isEmpty()) return;
        Glass.Style style = Prism.modules().get(ClickGui.class).panelStyle();
        for (Label l : labelList) {
            float w = mc.font.width(l.text()) + 14;
            float x = (float) l.x() - w / 2, y = (float) l.y() - 13;
            Glass.panel(ctx, x, y, w, 13, style);
            ctx.fill((int) x + 4, (int) y + 4, (int) x + 7, (int) y + 9, l.color());
            ctx.text(mc.font, l.text(), (int) x + 10, (int) y + 3, 0xFFFFFFFF, true);
        }
    }

    @Override
    public String getInfo() {
        if (!inEnd()) return "not in End";
        if (busy) return "...";
        int elytras = elytraFrames.size();
        for (StructurePredictor.Ship s : predictedShips) if (s.elytra() != null && !lootedConfirmed.contains(s.elytra()) && !near(elytraFrames, s.elytra(), 2)) elytras++;
        return elytras + " elytra";
    }
}

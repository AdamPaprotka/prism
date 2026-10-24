package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.mixin.LevelRendererAccessor;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Shows every block being mined right now, by anyone: the server tells every client within 32 blocks the break
 * stage (0-9) of each digger (ClientboundBlockDestructionPacket, kept in LevelRenderer.destroyingBlocks with our
 * own digging). The box grows from the middle and goes from Start to End colour; stages arrive in tenths, so the
 * shown progress eases toward the newest one instead of jumping.
 */
public class BreakIndicators extends Module {
    public final ColorSetting start = color("Start", 0x6050FF8C, "Colour when the break starts.");
    public final ColorSetting end = color("End", 0x80FF4D4D, "Colour when the block is about to break.");
    public final BoolSetting outline = bool("Outline", true, "Outline the box.");
    public final BoolSetting percent = bool("Percent", true, "Show the progress and who is mining it.");
    public final BoolSetting self = bool("Self", true, "Include your own mining.");
    public final BoolSetting throughWalls = bool("ThroughWalls", true, "Draw through blocks.");

    private record Shown(BlockPos pos, String who, float progress) {}

    /** Eased progress per block position, 0..1. */
    private final Long2ObjectOpenHashMap<float[]> eased = new Long2ObjectOpenHashMap<>();
    private final List<Shown> shown = new ArrayList<>();
    private long lastNanos;

    public BreakIndicators() { super("BreakIndicators", "Shows blocks being mined (by anyone) with their progress.", Category.RENDER); }

    @Override public void onDisable() { eased.clear(); shown.clear(); }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        shown.clear();
        var map = ((LevelRendererAccessor) mc.level).prism$destroyingBlocks();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet live = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (var entry : map.int2ObjectEntrySet()) {
            int id = entry.getIntKey();
            BlockDestructionProgress p = entry.getValue();
            if (!self.get() && id == mc.player.getId()) continue;
            BlockPos pos = p.getPos();
            if (mc.level.getBlockState(pos).isAir()) continue;
            float target = Mth.clamp((p.getProgress() + 1) / 10f, 0, 1);
            float[] e = eased.computeIfAbsent(pos.asLong(), k -> new float[]{Math.max(0, target - 0.1f)});
            e[0] += (target - e[0]) * (1 - (float) Math.exp(-dt * 10));
            live.add(pos.asLong());
            Entity who = mc.level.getEntity(id);
            shown.add(new Shown(pos, who == null ? "?" : who.getName().getString(), e[0]));
            draw(pos, e[0]);
        }
        eased.keySet().removeIf(k -> !live.contains(k));
    }

    private void draw(BlockPos pos, float progress) {
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        AABB full = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        Vec3 c = full.getCenter();
        double s = 0.15 + 0.85 * progress;
        AABB box = new AABB(c.x - full.getXsize() / 2 * s, c.y - full.getYsize() / 2 * s, c.z - full.getZsize() / 2 * s,
            c.x + full.getXsize() / 2 * s, c.y + full.getYsize() / 2 * s, c.z + full.getZsize() / 2 * s);
        int col = ColorUtil.lerp(start.color(), end.color(), progress);
        Render3D.box(box, col, outline.get() ? ColorUtil.withAlpha(col, 0xE0) : 0, throughWalls.get());
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (!percent.get()) return;
        for (Shown s : shown) {
            double[] sp = Render3D.project(Vec3.atCenterOf(s.pos()));
            if (sp == null) continue;
            String text = String.format(Locale.ROOT, "%d%% %s", Math.round(s.progress() * 100), s.who());
            int w = mc.font.width(text);
            ctx.text(mc.font, text, (int) sp[0] - w / 2, (int) sp[1] - 4, ColorUtil.lerp(start.color() | 0xFF000000, end.color() | 0xFF000000, s.progress()), true);
        }
    }

    @Override public String getInfo() { return shown.isEmpty() ? null : String.valueOf(shown.size()); }
}

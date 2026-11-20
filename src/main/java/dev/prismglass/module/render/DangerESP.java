package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Highlights what can kill you nearby. Explosives (crystals, TNT, creepers, charged respawn anchors outside the Nether,
 * beds in the Nether/End) get the damage they'd do to you right now (armour, blast protection and cover included);
 * anything that would kill you is marked lethal and pulses. Also projectiles flying at you, anvils/dripstone falling on
 * you, bosses, players holding a mace, crystals or anchors, lava hidden behind blocks near you, and the block you're
 * looking at when lava is right behind, beside or above it (mine it and the lava comes out).
 */
public class DangerESP extends Module {
    public final NumberSetting range = num("Range", 16, 4, 64, 1, "How far to look for threats.");
    public final ModeSetting boxMode = mode("Box", "Box", "Filled box with an outline, just the outline, or just the fill.", "Box", "Outline", "Fill");
    public final ModeSetting tracers = mode("Tracers", "Lethal", "Lines to threats: none, only ones that would kill you, or all.", "Off", "Lethal", "All");
    public final BoolSetting labels = bool("Labels", true, "Name and the damage it would do to you.");
    public final BoolSetting lethalOnly = bool("LethalOnly", false, "Only show threats that would kill you.");
    public final BoolSetting crystals = bool("Crystals", true, "End crystals (with their damage to you).");
    public final BoolSetting tnt = bool("TNT", true, "Lit TNT and TNT minecarts (with the fuse).");
    public final BoolSetting creepers = bool("Creepers", true, "Creepers (charged ones hit twice as hard).");
    public final BoolSetting anchors = bool("Anchors", true, "Charged respawn anchors outside the Nether and beds in the Nether/End: they blow up when used.");
    public final BoolSetting projectiles = bool("Projectiles", true, "Arrows, tridents, fireballs, wither skulls and shulker bullets flying at you.");
    public final BoolSetting falling = bool("Falling", true, "Anvils and pointed dripstone falling on you.");
    public final BoolSetting bosses = bool("Bosses", true, "Withers, wardens and the dragon.");
    public final BoolSetting armedPlayers = bool("ArmedPlayers", true, "Players (not friends) holding a mace, end crystals or respawn anchors.");
    public final BoolSetting lava = bool("Lava", true, "Lava hidden behind blocks near you, and a warning on the block you're looking at when lava is right behind, beside or above it.");
    public final NumberSetting lavaRange = num("LavaRange", 5, 2, 12, 1, "How far to look for hidden lava.").visibleWhen(lava::get);
    public final ColorSetting lavaColor = color("LavaColor", 0xFFFF8A1F, "Hidden lava.");
    public final ColorSetting dangerColor = color("DangerColor", 0xFFFFB347, "Threats that wouldn't kill you yet.");
    public final ColorSetting lethalColor = color("LethalColor", 0xFFFF3B3B, "Threats that would kill you.");
    public final NumberSetting fillAlpha = num("FillAlpha", 50, 0, 255, 1, "Box fill opacity.");

    /** One threat: its box (entity threats follow the entity smoothly), damage to you (-1 = not an explosion). */
    private record Threat(Entity entity, AABB box, String name, float damage, boolean lethal, int color) {}
    private record Label(String text, int color, double x, double y) {}

    private final List<Threat> threats = new ArrayList<>();
    private final List<BlockPos> blockThreats = new ArrayList<>();
    private final List<BlockPos> hiddenLava = new ArrayList<>();
    private final List<Label> labelList = new ArrayList<>();
    private int ticks;

    public DangerESP() { super("DangerESP", "Shows what can kill you: explosives with their damage, projectiles, falling anvils...", Category.RENDER); }

    @Override
    public void onTick() {
        if (++ticks % 10 == 1) scanBlocks();
        if (ticks % 5 == 1) scanLava();
        threats.clear();
        double r2 = range.get() * range.get();
        float hp = DamageUtil.health(mc.player);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.distanceToSqr(mc.player) > r2) continue;
            if (crystals.get() && e instanceof EndCrystal) add(e, e.getBoundingBox(), "Crystal", DamageUtil.crystalDamage(mc.player, e.position()), hp);
            else if (tnt.get() && e instanceof PrimedTnt t) add(e, e.getBoundingBox(), String.format("TNT %.1fs", t.getFuse() / 20.0), DamageUtil.explosionDamage(mc.player, e.position(), 4f), hp);
            else if (tnt.get() && e instanceof MinecartTNT) add(e, e.getBoundingBox(), "TNT minecart", DamageUtil.explosionDamage(mc.player, e.position(), 4f), hp);
            else if (creepers.get() && e instanceof Creeper c) {
                String name = (c.isPowered() ? "Charged creeper" : "Creeper") + (c.getSwelling(1f) > 0 ? " (hissing!)" : "");
                add(e, e.getBoundingBox(), name, DamageUtil.explosionDamage(mc.player, e.position(), c.isPowered() ? 6f : 3f), hp);
            } else if (projectiles.get() && isProjectile(e) && headingAtMe(e)) add(e, e.getBoundingBox().inflate(0.2), projectileName(e), -1, hp);
            else if (falling.get() && e instanceof FallingBlockEntity f && fallsOnMe(f)) add(e, e.getBoundingBox(), "Falling " + (f.getBlockState().getBlock() instanceof AnvilBlock ? "anvil" : "dripstone"), -1, hp);
            else if (bosses.get() && (e instanceof WitherBoss || e instanceof Warden || e instanceof EnderDragon)) add(e, e.getBoundingBox(), e.getName().getString(), -1, hp);
            else if (armedPlayers.get() && e instanceof Player p && !Prism.friends().isFriend(p) && armed(p) != null) add(e, e.getBoundingBox(), p.getGameProfile().name() + " (" + armed(p) + ")", -1, hp);
        }
        for (BlockPos pos : blockThreats) {
            BlockState s = mc.level.getBlockState(pos);
            Vec3 c = Vec3.atCenterOf(pos);
            if (s.getBlock() instanceof RespawnAnchorBlock) add(null, new AABB(pos), "Charged anchor", DamageUtil.anchorDamage(mc.player, c), hp);
            else if (s.getBlock() instanceof BedBlock) add(null, new AABB(pos), "Bed (explodes here)", DamageUtil.bedDamage(mc.player, c), hp);
        }
        if (lava.get()) {
            if (!lethalOnly.get()) for (BlockPos pos : hiddenLava) threats.add(new Threat(null, new AABB(pos), "", -1, false, lavaColor.color()));
            lavaBehindTarget();
        }
    }

    /** Lava within LavaRange you can't see from your eyes (a straight look at it hits another block first). */
    private void scanLava() {
        hiddenLava.clear();
        if (!lava.get()) return;
        int r = lavaRange.getInt();
        Vec3 eye = mc.player.getEyePosition();
        BlockPos me = mc.player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            pos.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
            if (!mc.level.getFluidState(pos).is(FluidTags.LAVA)) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) continue; // in plain sight
            hiddenLava.add(pos.immutable());
            if (hiddenLava.size() >= 300) return;
        }
    }

    /** The block you're looking at, when breaking it would open it onto lava (behind, beside or above it). */
    private void lavaBehindTarget() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos target = hit.getBlockPos();
        if (mc.level.getFluidState(target).is(FluidTags.LAVA)) return;
        String where = null;
        for (Direction d : Direction.values()) {
            if (d == hit.getDirection()) continue; // the face you're looking at is on your side
            if (!mc.level.getFluidState(target.relative(d)).is(FluidTags.LAVA)) continue;
            where = d == Direction.UP ? "above" : d == hit.getDirection().getOpposite() ? "behind" : "beside";
            if (d == Direction.UP) break; // lava above pours straight onto you: the worst one
        }
        if (where != null) threats.add(new Threat(null, new AABB(target), "Lava " + where + "!", -1, true, 0));
    }

    private void add(Entity e, AABB box, String name, float damage, float hp) {
        boolean lethal = damage >= 0 && damage >= hp;
        if (lethalOnly.get() && !lethal) return;
        threats.add(new Threat(e, box, name, damage, lethal, 0));
    }

    /** Charged anchors outside the Nether and beds in the Nether/End, within range (scanned every half second). */
    private void scanBlocks() {
        blockThreats.clear();
        if (!anchors.get()) return;
        boolean nether = mc.level.dimension() == Level.NETHER, overworld = mc.level.dimension() == Level.OVERWORLD;
        int r = range.getInt();
        BlockPos me = mc.player.blockPosition();
        int cx0 = (me.getX() - r) >> 4, cx1 = (me.getX() + r) >> 4, cz0 = (me.getZ() - r) >> 4, cz1 = (me.getZ() + r) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection sec = sections[i];
                if (sec == null || sec.hasOnlyAir()) continue;
                int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                if (baseY + 15 < me.getY() - r || baseY > me.getY() + r) continue;
                if (!sec.maybeHas(st -> st.is(Blocks.RESPAWN_ANCHOR) || st.getBlock() instanceof BedBlock)) continue;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState s = sec.getBlockState(x, y, z);
                    boolean anchor = s.is(Blocks.RESPAWN_ANCHOR) && !nether && s.getValue(RespawnAnchorBlock.CHARGE) > 0;
                    boolean bed = s.getBlock() instanceof BedBlock && !overworld;
                    if (!anchor && !bed) continue;
                    BlockPos p = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                    if (p.distSqr(me) <= (double) r * r) blockThreats.add(p);
                }
            }
        }
    }

    private static boolean isProjectile(Entity e) {
        return e instanceof AbstractArrow || e instanceof AbstractHurtingProjectile || e instanceof ShulkerBullet;
    }

    private static String projectileName(Entity e) {
        return e.getType().getDescription().getString();
    }

    /** Moving and its straight path passes within 2.5 blocks of you (ahead of it, not behind). */
    private boolean headingAtMe(Entity e) {
        Vec3 v = e.getDeltaMovement();
        if (v.lengthSqr() < 0.01) return false; // stuck in the ground
        Vec3 to = mc.player.getBoundingBox().getCenter().subtract(e.position());
        double t = to.dot(v) / v.lengthSqr();
        if (t <= 0) return false;
        return to.subtract(v.scale(t)).lengthSqr() < 2.5 * 2.5;
    }

    private boolean fallsOnMe(FallingBlockEntity f) {
        var b = f.getBlockState().getBlock();
        if (!(b instanceof AnvilBlock) && !(b instanceof PointedDripstoneBlock)) return false;
        double dx = f.getX() - mc.player.getX(), dz = f.getZ() - mc.player.getZ();
        return f.getY() > mc.player.getY() && dx * dx + dz * dz < 1.5 * 1.5;
    }

    /** What makes this player dangerous to stand next to, or null. */
    private static String armed(Player p) {
        for (var stack : new net.minecraft.world.item.ItemStack[]{p.getMainHandItem(), p.getOffhandItem()}) {
            if (stack.is(Items.MACE)) return "mace";
            if (stack.is(Items.END_CRYSTAL)) return "crystals";
            if (stack.is(Items.RESPAWN_ANCHOR)) return "anchors";
        }
        return null;
    }

    // ---- render --------------------------------------------------------------------------------------------------

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        labelList.clear();
        // lethal threats pulse so they stand out
        double pulse = 0.65 + 0.35 * Math.sin(System.currentTimeMillis() / 120.0);
        for (Threat t : threats) {
            AABB box = t.entity() != null ? Render3D.lerpBox(t.entity(), delta) : t.box();
            int col = t.color() != 0 ? t.color() : t.lethal() ? lethalColor.color() : dangerColor.color();
            int fillA = (int) (fillAlpha.getInt() * (t.lethal() ? pulse : 1));
            int fill = boxMode.is("Outline") ? 0 : ColorUtil.withAlpha(col, fillA);
            int line = boxMode.is("Fill") ? 0 : t.lethal() ? ColorUtil.withAlpha(col, (int) (255 * pulse)) : col;
            Render3D.box(box, fill, line, true);
            Vec3 c = box.getCenter();
            if (tracers.is("All") || tracers.is("Lethal") && t.lethal()) Render3D.tracer(c, ColorUtil.withAlpha(col, 200));
            if (labels.get() && !t.name().isEmpty()) { // hidden lava: just the boxes
                double[] s = Render3D.project(new Vec3(c.x, box.maxY + 0.5, c.z));
                if (s != null) {
                    String dmg = t.damage() < 0 ? "" : t.lethal() ? " §c§lLETHAL " + fmt(t.damage()) : " §7" + fmt(t.damage());
                    labelList.add(new Label(t.name() + dmg, col, s[0], s[1]));
                }
            }
        }
    }

    private static String fmt(float damage) {
        return String.format("%.1f❤", damage / 2f);
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        for (Label l : labelList) {
            int w = mc.font.width(l.text());
            int x = (int) l.x() - w / 2, y = (int) l.y() - 10;
            ctx.fill(x - 3, y - 2, x + w + 3, y + 10, 0x90000000);
            ctx.fill(x - 3, y - 2, x - 1, y + 10, l.color());
            ctx.text(mc.font, l.text(), x, y, 0xFFFFFFFF, true);
        }
    }

    @Override
    public String getInfo() {
        long lethal = threats.stream().filter(Threat::lethal).count();
        long named = threats.stream().filter(t -> !t.name().isEmpty()).count();
        return named == 0 ? null : lethal > 0 ? lethal + " lethal" : String.valueOf(named);
    }
}

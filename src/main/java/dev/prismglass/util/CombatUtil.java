package dev.prismglass.util;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.tags.ItemTags;

import dev.prismglass.Prism;
import dev.prismglass.module.client.AntiCheat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Attack/crystal helpers shared by all combat modules. Every hit goes through {@link #attack}. */
public final class CombatUtil {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Timer attackTimer = new Timer();

    private CombatUtil() {}

    public static boolean cooldownReady() {
        if (!Prism.anticheat().attackCooldown.get()) return true;
        return mc.player.getAttackStrengthScale(0.5f) >= 0.93f;
    }

    /**
     * Attacks if every AntiCheat rule allows it right now:
     * min delay (NCP Angle), one target per tick + no action after the flying packet (Grim
     * MultiInteractA / PacketOrderO), and the sent rotation facing the hitbox within range
     * (Grim Reach/Hitbox). Attack packet is followed by the swing, as vanilla does (PacketOrderB).
     */
    public static boolean attack(Entity target, boolean requireCooldown) {
        AntiCheat ac = Prism.anticheat();
        if (requireCooldown && !cooldownReady()) return false;
        if (requireCooldown && !Prism.humanizer().hitReady()) return false; // varied, drifting gaps between hits
        if (!attackTimer.passed(ac.minAttackDelay.get())) return false;
        if (!Prism.guard().canInteractEntity(target.getId())) return false;
        double range = ac.range(6.0);
        dev.prismglass.module.player.Reach reachMod = Prism.modules().get(dev.prismglass.module.player.Reach.class);
        boolean ncpReach = reachMod != null && reachMod.isEnabled() && reachMod.mode.is("NCP");
        if (ac.preset.is("NCP") || ncpReach) range = Math.min(range, Prism.reachBudget().ncpAllowed());
        // the box the server accepts (Grim: drawn + newest server position, plus its idle margin)
        AABB box = HitboxUtil.attackBox(target);
        if (ac.strictRaycast.get()) {
            // Grim Reach: distance along the look ray we already sent, not to the closest point
            double d = Prism.rotations().rayDistance(box, range);
            if (d < 0 || d > range) return false;
        } else if (RotationUtil.distanceToBox(mc.player.getEyePosition(), box) > range) {
            return false;
        }
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        attackTimer.reset();
        if (requireCooldown) Prism.humanizer().onHit();
        return true;
    }

    /** Faces the closest point of the target's hitbox (silent rotation). */
    public static void face(Entity target, int priority) {
        Vec3 eye = mc.player.getEyePosition();
        AABB box = HitboxUtil.attackBox(target);
        Vec3 closest = RotationUtil.closestPoint(box, eye);
        Prism.rotations().request(Prism.humanizer().aimPoint(box.deflate(box.getXsize() * 0.15, box.getYsize() * 0.1, box.getZsize() * 0.15), eye, closest), priority);
    }

    // ---- weapons -----------------------------------------------------------------------------

    public static boolean isWeapon(ItemStack s) {
        return s.is(net.minecraft.tags.ItemTags.SWORDS) || s.getItem() instanceof AxeItem
            || s.getItem() instanceof MaceItem || s.getItem() instanceof TridentItem;
    }

    /** Best hotbar weapon by attack damage attribute (+ sharpness level). */
    public static int bestWeapon(boolean preferAxe) {
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!isWeapon(s)) continue;
            double score = attackDamage(s);
            if (preferAxe && s.getItem() instanceof AxeItem) score += 100;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    public static double attackDamage(ItemStack s) {
        double dmg = 1;
        var mods = s.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
            net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        for (var e : mods.modifiers()) {
            if (e.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) dmg += e.modifier().amount();
        }
        return dmg;
    }

    // ---- crystals ----------------------------------------------------------------------------

    /** 1.13+ rules: base is obsidian/bedrock, block above is air, nothing in the 1x2 space above. */
    public static boolean canPlaceCrystal(BlockPos base, boolean ignoreCrystals) {
        BlockState s = mc.level.getBlockState(base);
        if (!s.is(Blocks.OBSIDIAN) && !s.is(Blocks.BEDROCK)) return false;
        BlockPos up = base.above();
        if (!mc.level.getBlockState(up).isAir()) return false;
        AABB space = new AABB(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
        for (Entity e : mc.level.getEntities(null, space)) {
            if (ignoreCrystals && e instanceof EndCrystal) continue;
            if (e.isAlive() && !e.isSpectator()) return false;
        }
        return true;
    }

    public static Vec3 crystalPos(BlockPos base) { return new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5); }

    public static InteractionHand crystalHand() {
        if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) return InteractionHand.OFF_HAND;
        if (mc.player.getMainHandItem().is(Items.END_CRYSTAL)) return InteractionHand.MAIN_HAND;
        return null;
    }
}

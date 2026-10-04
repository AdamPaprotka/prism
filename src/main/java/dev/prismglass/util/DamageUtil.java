package dev.prismglass.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Client-side explosion damage estimate (crystals, anchors, beds). */
public final class DamageUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private DamageUtil() {}

    public static float crystalDamage(LivingEntity target, Vec3 crystal) { return explosionDamage(target, crystal, 6f); }

    public static float anchorDamage(LivingEntity target, Vec3 pos) { return explosionDamage(target, pos, 5f); }

    public static float bedDamage(LivingEntity target, Vec3 pos) { return explosionDamage(target, pos, 5f); }

    public static float explosionDamage(LivingEntity target, Vec3 pos, float power) {
        return target == null ? 0f : explosionDamage(target, pos, power, target.position());
    }

    /** Crystal damage with the target standing at {@code feet} instead of where it is now (movement prediction). */
    public static float crystalDamageAt(LivingEntity target, Vec3 crystal, Vec3 feet) { return explosionDamage(target, crystal, 6f, feet); }

    /** Upper bound (full exposure) - lets callers skip the raycasts for spots that can't reach a minimum anyway. */
    public static float crystalDamageMax(LivingEntity target, Vec3 crystal, Vec3 feet) {
        double q = 12.0, dist = Math.sqrt(feet.distanceToSqr(crystal)) / q;
        if (dist > 1.0) return 0f;
        double impact = 1.0 - dist;
        return scaleAndReduce(target, (float) ((impact * impact + impact) / 2.0 * 7.0 * q + 1.0));
    }

    public static float explosionDamage(LivingEntity target, Vec3 pos, float power, Vec3 feet) {
        if (target == null || (target instanceof Player p && p.getAbilities().instabuild)) return 0f;
        double q = power * 2.0;
        double dist = Math.sqrt(feet.distanceToSqr(pos)) / q;
        if (dist > 1.0) return 0f;

        AABB box = target.getBoundingBox().move(feet.subtract(target.position()));
        double exposure = seenPercent(pos, box, target);
        double impact = (1.0 - dist) * exposure;
        return scaleAndReduce(target, (float) ((impact * impact + impact) / 2.0 * 7.0 * q + 1.0));
    }

    /** Vanilla ServerExplosion#getSeenPercent, for any box (the target's predicted one). */
    public static float seenPercent(Vec3 center, AABB bb, net.minecraft.world.entity.Entity entity) {
        double xs = 1.0 / ((bb.maxX - bb.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((bb.maxY - bb.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((bb.maxZ - bb.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) return 0f;
        int hits = 0, count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    Vec3 from = new Vec3(net.minecraft.util.Mth.lerp(xx, bb.minX, bb.maxX) + xOffset,
                        net.minecraft.util.Mth.lerp(yy, bb.minY, bb.maxY), net.minecraft.util.Mth.lerp(zz, bb.minZ, bb.maxZ) + zOffset);
                    if (entity.level().clip(new net.minecraft.world.level.ClipContext(from, center, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, entity)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    private static float scaleAndReduce(LivingEntity target, float damage) {
        if (target instanceof Player) {
            Difficulty d = mc.level.getDifficulty();
            if (d == Difficulty.PEACEFUL) damage = 0f;
            else if (d == Difficulty.EASY) damage = Math.min(damage / 2f + 1f, damage);
            else if (d == Difficulty.HARD) damage *= 1.5f;
        }
        return reduce(target, damage);
    }

    /** Armour, toughness, resistance and blast protection. */
    private static float reduce(LivingEntity target, float damage) {
        float armor = (float) target.getArmorValue();
        float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float f = 2f + toughness / 4f;
        float effective = Math.max(armor - damage / f, armor * 0.2f);
        damage *= 1f - Math.min(20f, effective) / 25f;

        MobEffectInstance res = target.getEffect(MobEffects.RESISTANCE);
        if (res != null) damage *= Math.max(0f, 1f - (res.getAmplifier() + 1) * 0.2f);

        int epf = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = target.getItemBySlot(slot);
            epf += level(stack, Enchantments.PROTECTION);
            epf += level(stack, Enchantments.BLAST_PROTECTION) * 2;
        }
        damage *= 1f - Math.min(20, epf) / 25f;
        return Math.max(0f, damage);
    }

    private static int level(ItemStack stack, ResourceKey<Enchantment> key) {
        ItemEnchantments ench = stack.get(DataComponents.ENCHANTMENTS);
        if (ench == null) return 0;
        for (Holder<Enchantment> e : ench.keySet()) {
            if (e.is(key)) return ench.getLevel(e);
        }
        return 0;
    }

    public static float health(LivingEntity e) { return e.getHealth() + e.getAbsorptionAmount(); }
}

package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.manager.MovementHooks;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.util.CombatUtil;
import dev.prismglass.util.RotationUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Critical hits.
 * <ul>
 *   <li>Legit: only hit in the air once a crit is real AND legal - falling, after NCP's max jump phase
 *       (Updated-NCP fight.Critical flags crits within the first 6 ticks of a jump, Jump Boost raises that).</li>
 *   <li>Jump: like Legit, but taps jump for you (real input) when a hit is coming up, so every hit is a crit.
 *       Fully legit on NCP and Grim.</li>
 *   <li>Packet / NCP: fake tiny hops. [Flagged by Grim and current NCP fight.Critical]</li>
 * </ul>
 */
public class Criticals extends Module {
    public final ModeSetting mode = mode("Mode", "Jump", "Jump/Legit are legal crits; Packet/NCP fake them (flag).", "Jump", "Legit", "Packet", "NCP");
    private static Criticals instance;
    private int airTicks;
    private long groundWaitSince = -1;

    public Criticals() {
        super("Criticals", "Land critical hits.", Category.COMBAT);
        instance = this;
    }

    @Override
    public void onTick() {
        var p = mc.player;
        airTicks = p.onGround() ? 0 : airTicks + 1;
        if (!mode.is("Jump") || !p.onGround()) return;
        // a hit is coming up soon: jump now so we're falling (crit window) when the cooldown is full
        KillAura ka = Prism.modules().get(KillAura.class);
        LivingEntity t = ka != null && ka.isEnabled() ? ka.getTarget() : null;
        if (t == null || RotationUtil.distanceTo(t) > Prism.anticheat().range(ka.range.get()) + 0.5) return;
        if (mc.player.getAttackStrengthScale(0.5f) >= 0.42f && canCritNow()) MovementHooks.requestJump();
    }

    /** No crit is possible at all in these states, so don't hold hits back. */
    private static boolean canCritNow() {
        var p = mc.player;
        return !p.isInWater() && !p.isInLava() && !p.onClimbable() && !p.isPassenger() && !p.getAbilities().flying
            && !p.hasEffect(MobEffects.BLINDNESS) && !p.hasEffect(MobEffects.SLOW_FALLING);
    }

    private int maxJumpPhase() {
        var boost = mc.player.getEffect(MobEffects.JUMP_BOOST);
        if (boost == null) return 6;
        return (int) Math.round((0.5 + boost.getAmplifier() + 1) * 6);
    }

    /** KillAura/Trigger ask this before attacking. */
    public static boolean allowsAttack() {
        if (instance == null || !instance.isEnabled()) return true;
        String m = instance.mode.get();
        if (!m.equals("Legit") && !m.equals("Jump")) return true;
        var p = mc.player;
        if (!canCritNow()) return true;
        if (p.onGround()) {
            if (!m.equals("Jump")) return true;
            // Jump mode: wait for the jump, but never stall a ready hit forever (ceiling, stuck...)
            if (!CombatUtil.cooldownReady()) { instance.groundWaitSince = -1; return false; }
            if (instance.groundWaitSince < 0) instance.groundWaitSince = System.currentTimeMillis();
            return System.currentTimeMillis() - instance.groundWaitSince > 650;
        }
        instance.groundWaitSince = -1;
        // real crit: falling (the server tracks fall distance from our packets; the client's own fallDistance
        // stays 0 on 1.21.4, so velocity is the signal); legal crit: past NCP's max jump phase (+1 tick margin)
        return p.getDeltaMovement().y < -0.05 && instance.airTicks >= instance.maxJumpPhase() + 2;
    }

    @Override
    public void onAttack(Entity target) {
        if (mode.is("Legit") || mode.is("Jump") || !(target instanceof LivingEntity) || !mc.player.onGround()) return;
        if (mc.player.isInWater() || mc.player.isInLava()) return;
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        double[] offsets = mode.is("NCP") ? new double[]{0.11, 0.1100013579, 0.0000013579} : new double[]{0.0625, 0};
        for (double o : offsets) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + o, z, false, mc.player.horizontalCollision));
        }
    }

    @Override public String getInfo() { return mode.get(); }
}

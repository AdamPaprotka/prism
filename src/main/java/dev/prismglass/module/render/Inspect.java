package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * CS:GO-style inspect of the item in your hand (key: ClickGUI → Keybinds → Inspect, default I).
 *
 * <p>Two layers, both offsets from the vanilla pose so every clip starts and ends exactly on it:
 * <ul>
 *   <li><b>Hand</b> (ItemInHandRendererMixin): moves and turns the whole held item in camera space, pivoting where
 *       the hand sits on screen. This is the "bring it up and look at it" motion.</li>
 *   <li><b>Item</b> (ItemTransformMixin): turns the model about its own axes after its first-person display
 *       transform: a spin about the clip's axis (the blade diagonal for swords and tools, so a flip really rolls the
 *       blade over), a twirl in the sprite's plane about the grip, and a turn about the vertical to show the back.</li>
 * </ul>
 * Each clip is keyframes on 9 channels, interpolated with a time-aware Catmull-Rom (Hermite with finite-difference
 * tangents), so motion flows through keys instead of stopping at each one; keys marked stop get a zero tangent for a
 * deliberate catch. Angles that end a clip on a multiple of 360 are equivalent to rest.
 *
 * <p>Special 3D models (trident) aren't centred on their model origin, so their twirl is done in the hand layer
 * (a baton spin about the grip) instead of the item layer.
 */
public class Inspect extends Module {
    public final ModeSetting style = mode("Style", "Auto", "Auto picks an animation for the item (swords alternate flip and twirl). The others force one for everything.",
        "Auto", "Flip", "Twirl", "Toss", "Showcase");
    public final NumberSetting speed = num("Speed", 1.0, 0.5, 2.0, 0.05, "Playback speed.");
    public final BoolSetting sway = bool("Sway", true, "Slight breathing sway while inspecting so it never looks robotic.");
    public final BoolSetting sounds = bool("Sounds", true, "Quiet whoosh / catch sounds at the flips (only you hear them).");
    public final BoolSetting cancelOnSwing = bool("CancelOnSwing", true, "Attacking or mining ends the inspect (blends back quickly).");
    public final BoolSetting cancelOnSwap = bool("CancelOnSwap", true, "Switching items ends the inspect.");
    public final BoolSetting offhand = bool("Offhand", true, "With an empty main hand, inspect the offhand item (totem...) instead of your fist.");

    // channels: hand translate (x toward the screen centre is negative), hand rotate, item spin / twirl / turn
    static final int TX = 0, TY = 1, TZ = 2, RX = 3, RY = 4, RZ = 5, SPIN = 6, TWIRL = 7, TURN = 8, N = 9;
    private static final int SND_NONE = 0, SND_WHOOSH = 1, SND_CATCH = 2, SND_CLOTH = 3;
    private static final float CROSSFADE = 0.15f, FADE_OUT = 0.18f;

    /** Item layer state for the mixins: true only while the inspected hand renders. */
    public static boolean itemLayer;
    private static final float[] pose = new float[N];
    private static Clip active;

    private Clip clip;
    private InteractionHand hand = InteractionHand.MAIN_HAND;
    private long startNanos = -1, fadeNanos = -1;
    private float[] from, fadeFrom;
    private ItemStack startStack = ItemStack.EMPTY;
    private int startSlot, lastSoundKey, swordVariant;

    public Inspect() {
        super("Inspect", "CS:GO-style inspect animations for the item in your hand (Keybinds tab: Inspect).", Category.RENDER);
        drawn.set(false);
        setEnabledSilently(true);
    }

    // ---- control ----------------------------------------------------------------------------------------

    /** The Inspect key: start, or restart with a short crossfade from wherever the item is now. */
    public void trigger() {
        if (!isEnabled() || mc.player == null) return;
        var p = mc.player;
        hand = InteractionHand.MAIN_HAND;
        ItemStack stack = p.getMainHandItem();
        if (stack.isEmpty() && offhand.get() && !p.getOffhandItem().isEmpty()) {
            hand = InteractionHand.OFF_HAND;
            stack = p.getOffhandItem();
        }
        float[] now = currentPose();
        from = now == null ? null : wrapped(now);
        fadeNanos = -1;
        clip = pick(stack);
        startStack = stack.copy();
        startSlot = p.getInventory().getSelectedSlot();
        startNanos = System.nanoTime();
        lastSoundKey = 0;
    }

    public boolean inspecting() { return clip != null || fadeNanos >= 0; }

    /** Length of the clip playing now at the current speed, in seconds (0 when idle). */
    public float clipSeconds() { return clip == null ? 0 : clip.seconds / speed.getFloat(); }

    /** Name of the clip playing now, for logs. */
    public String clipName() {
        for (var e : CLIPS.entrySet()) if (e.getValue() == clip) return e.getKey();
        return "none";
    }

    /** Blend back to the vanilla pose from wherever we are. */
    public void cancel() {
        if (clip == null) return;
        float[] now = currentPose();
        clip = null;
        if (now == null) return;
        fadeFrom = wrapped(now);
        fadeNanos = System.nanoTime();
    }

    @Override public void onDisable() { clip = null; fadeNanos = -1; active = null; }

    @Override
    public void onTick() {
        if (fadeNanos >= 0 && (System.nanoTime() - fadeNanos) / 1e9f >= FADE_OUT) fadeNanos = -1;
        if (clip == null) return;
        // the render hook ends clips too, but the hand isn't drawn in third person or with F1
        if (progress(System.nanoTime()) >= 1) { clip = null; return; }
        var p = mc.player;
        if (cancelOnSwing.get() && (p.isSwinging() || p.isUsingItem())) { cancel(); return; }
        if (cancelOnSwap.get()) {
            ItemStack now = hand == InteractionHand.MAIN_HAND ? p.getMainHandItem() : p.getOffhandItem();
            if (p.getInventory().getSelectedSlot() != startSlot || !ItemStack.isSameItem(now, startStack)) { cancel(); return; }
        }
        // sounds: any key with a sound that playback passed since last tick
        if (sounds.get()) {
            float u = progress(System.nanoTime());
            Key[] keys = clip.keys;
            while (lastSoundKey < keys.length && keys[lastSoundKey].t <= u) {
                play(keys[lastSoundKey].sound);
                lastSoundKey++;
            }
        }
    }

    private void play(int sound) {
        switch (sound) {
            case SND_WHOOSH -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_SWEEP, 1.75f, 0.10f));
            case SND_CATCH -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.9f, 0.12f));
            case SND_CLOTH -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 1.3f, 0.22f));
            default -> {}
        }
    }

    private float progress(long now) { return (now - startNanos) / 1e9f * speed.getFloat() / clip.seconds; }

    /** This frame's pose, or null at rest. */
    private float[] currentPose() {
        long now = System.nanoTime();
        if (clip != null) {
            float u = progress(now);
            if (u >= 1) { clip = null; return null; }
            float[] p = clip.sample(u);
            if (from != null) {
                float e = (now - startNanos) / 1e9f / CROSSFADE;
                if (e < 1) { float k = smooth(e); for (int i = 0; i < N; i++) p[i] = Mth.lerp(k, from[i], p[i]); }
                else from = null;
            }
            if (sway.get()) {
                float s = (now - startNanos) / 1e9f, env = Mth.sin(Mth.PI * Math.min(u, 1));
                p[TY] += env * 0.006f * Mth.sin(s * 5.0f);
                p[RX] += env * 0.8f * Mth.sin(s * 3.4f + 0.7f);
                p[RZ] += env * 0.6f * Mth.sin(s * 2.8f + 1.9f);
            }
            return p;
        }
        if (fadeNanos >= 0) {
            float f = (now - fadeNanos) / 1e9f / FADE_OUT;
            if (f >= 1) { fadeNanos = -1; return null; }
            float k = 1 - smooth(f);
            float[] p = new float[N];
            for (int i = 0; i < N; i++) p[i] = fadeFrom[i] * k;
            return p;
        }
        return null;
    }

    private static float smooth(float x) { x = Mth.clamp(x, 0, 1); return x * x * (3 - 2 * x); }

    /** Angles folded into -180..180 (a full turn looks like none) so fades never spin the long way. */
    private static float[] wrapped(float[] p) {
        float[] w = p.clone();
        for (int i = RX; i <= TURN; i++) w[i] = Mth.wrapDegrees(w[i]);
        return w;
    }

    // ---- render hooks (called from the mixins) ----------------------------------------------------------

    /**
     * ItemInHandRenderer.renderArmWithItem, right after its pushPose: applies the hand layer when this is the
     * inspected hand and arms the item layer for the item drawn next.
     */
    public void applyHand(InteractionHand renderHand, HumanoidArm arm, PoseStack poseStack) {
        itemLayer = false;
        if (renderHand != hand || !isEnabled()) return;
        Clip c = clip;
        float[] p = currentPose();
        if (p == null) { active = null; return; }
        active = c != null ? c : active;
        System.arraycopy(p, 0, pose, 0, N);
        float side = arm == HumanoidArm.RIGHT ? 1f : -1f;
        // pivot: where vanilla's arm transform puts the hand
        float px = side * 0.56f, py = -0.52f, pz = -0.72f;
        poseStack.translate(px + side * p[TX], py + p[TY], pz + p[TZ]);
        poseStack.rotate(Axis.XP.rotationDegrees(p[RX]));
        poseStack.rotate(Axis.YP.rotationDegrees(side * p[RY]));
        poseStack.rotate(Axis.ZP.rotationDegrees(side * p[RZ]));
        poseStack.translate(-px, -py, -pz);
        itemLayer = active != null;
    }

    private static final Quaternionf Q = new Quaternionf();

    /** ItemTransform.apply, after the display transform and before the model is centred: the item layer. */
    public static void applyItem(PoseStack.Pose p) {
        Clip c = active;
        if (c == null) return;
        if (pose[TURN] != 0) p.rotate(Q.rotationY(pose[TURN] * Mth.DEG_TO_RAD));
        if (pose[TWIRL] != 0) p.rotateAround(Q.rotationZ(pose[TWIRL] * Mth.DEG_TO_RAD), c.pivot.x, c.pivot.y, c.pivot.z);
        if (pose[SPIN] != 0) p.rotate(Q.rotationAxis(pose[SPIN] * Mth.DEG_TO_RAD, c.axis.x, c.axis.y, c.axis.z));
    }

    // ---- clips ------------------------------------------------------------------------------------------

    /** One keyframe: normalised time, the 9 channel values, a zero-tangent stop, and a sound when it's reached. */
    record Key(float t, float[] v, boolean stop, int sound) {}

    /** A whole animation. {@code axis} is the item spin axis and {@code pivot} the twirl pivot, both in model space. */
    static final class Clip {
        final float seconds;
        final Vector3f axis, pivot;
        final Key[] keys;
        private final float[][] tangents;

        Clip(float seconds, Vector3f axis, Vector3f pivot, Key... keys) {
            this.seconds = seconds;
            this.axis = axis.normalize();
            this.pivot = pivot;
            this.keys = keys;
            // time-aware Catmull-Rom tangents; first, last and stop keys are flat
            tangents = new float[keys.length][N];
            for (int i = 1; i < keys.length - 1; i++) {
                if (keys[i].stop) continue;
                float dt = keys[i + 1].t - keys[i - 1].t;
                for (int ch = 0; ch < N; ch++) tangents[i][ch] = (keys[i + 1].v[ch] - keys[i - 1].v[ch]) / dt;
            }
        }

        float[] sample(float u) {
            int i = 0;
            while (i < keys.length - 2 && u > keys[i + 1].t) i++;
            Key a = keys[i], b = keys[i + 1];
            float h = b.t - a.t, s = Mth.clamp((u - a.t) / h, 0, 1);
            float s2 = s * s, s3 = s2 * s;
            float h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
            float[] out = new float[N];
            for (int ch = 0; ch < N; ch++) {
                out[ch] = h00 * a.v[ch] + h10 * h * tangents[i][ch] + h01 * b.v[ch] + h11 * h * tangents[i + 1][ch];
            }
            return out;
        }
    }

    private static Key k(float t, float tx, float ty, float tz, float rx, float ry, float rz, float spin, float twirl, float turn) {
        return new Key(t, new float[]{tx, ty, tz, rx, ry, rz, spin, twirl, turn}, false, SND_NONE);
    }

    private static Key stop(Key k) { return new Key(k.t, k.v, true, k.sound); }

    private static Key snd(Key k, int sound) { return new Key(k.t, k.v, k.stop, sound); }

    private static final Vector3f BLADE = new Vector3f(1, 1, 0), X = new Vector3f(1, 0, 0), Y = new Vector3f(0, 1, 0);
    private static final Vector3f GRIP = new Vector3f(-0.3f, -0.3f, 0), CENTRE = new Vector3f();

    /** Swords: lift, show the flat, roll it over along the blade, tip up along the edge, twirl round the grip, roll back. */
    static final Clip SWORD_FLIP = new Clip(2.6f, BLADE, GRIP,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.10f, -0.10f, 0.06f, 0.04f, -8, -12, 8, 0, 0, 0),
        k(0.24f, -0.16f, 0.10f, 0.08f, -12, -38, 18, 0, 0, -25),
        snd(k(0.40f, -0.16f, 0.11f, 0.08f, -10, -40, 18, 180, 0, -25), SND_WHOOSH),
        stop(k(0.52f, -0.15f, 0.10f, 0.07f, -16, -36, 22, 180, 0, -25)),
        k(0.66f, -0.12f, 0.14f, 0.05f, -32, -20, 12, 180, 0, 0),
        snd(k(0.80f, -0.08f, 0.08f, 0.03f, -6, -6, 6, 180, -360, 0), SND_WHOOSH),
        k(0.91f, 0.01f, -0.03f, 0, 4, 2, -2, 360, -360, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 360, -360, 0));

    /** Swords, second variant: a quick twirl, a catch, then a flip to show the other side. */
    static final Clip SWORD_TWIRL = new Clip(2.1f, BLADE, GRIP,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.12f, -0.06f, 0.05f, 0.03f, -6, -10, 6, 0, 0, 0),
        snd(k(0.38f, -0.08f, 0.08f, 0.04f, -10, -16, 10, 0, -360, 0), SND_WHOOSH),
        snd(stop(k(0.50f, -0.08f, 0.07f, 0.04f, -8, -16, 10, 0, -360, 0)), SND_CATCH),
        k(0.72f, -0.12f, 0.10f, 0.06f, -14, -34, 16, 180, -360, -20),
        k(0.90f, 0, -0.02f, 0, 3, 0, 0, 360, -360, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 360, -360, 0));

    /** Axes: turn the head toward you, weigh it (dip and bounce), roll it over, blade up, settle. */
    static final Clip AXE = new Clip(2.4f, BLADE, GRIP,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.14f, -0.10f, 0.08f, 0.05f, -10, -30, 14, 0, 0, 0),
        k(0.30f, -0.14f, 0.10f, 0.07f, -14, -62, 24, 0, 0, -20),
        k(0.40f, -0.14f, 0.06f, 0.07f, -10, -62, 24, 0, 0, -20),
        snd(k(0.48f, -0.14f, 0.11f, 0.07f, -16, -62, 24, 0, 0, -20), SND_CLOTH),
        snd(k(0.64f, -0.12f, 0.10f, 0.06f, -14, -40, 18, 180, 0, -20), SND_WHOOSH),
        k(0.80f, -0.08f, 0.12f, 0.04f, -28, -18, 8, 180, 0, 0),
        k(0.92f, 0.01f, -0.03f, 0, 4, 0, -2, 360, 0, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 360, 0, 0));

    /** Pickaxes, shovels, hoes: tip up to look at the head, turn it round, tap down, bounce. */
    static final Clip TOOL = new Clip(2.2f, BLADE, GRIP,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.14f, -0.08f, 0.08f, 0.05f, -14, -24, 12, 0, 0, 0),
        k(0.30f, -0.12f, 0.12f, 0.06f, -34, -30, 16, 0, 0, 0),
        snd(k(0.46f, -0.12f, 0.12f, 0.06f, -34, -30, 16, 0, 0, 180), SND_WHOOSH),
        snd(stop(k(0.60f, -0.10f, 0.04f, 0.05f, 10, -20, 10, 0, 0, 180)), SND_CATCH),
        k(0.68f, -0.10f, 0.08f, 0.05f, -6, -20, 10, 0, 0, 180),
        k(0.86f, 0, -0.02f, 0, 3, 0, 0, 0, 0, 360),
        k(1f, 0, 0, 0, 0, 0, 0, 0, 0, 360));

    /** Mace: a slow heave, the weight drops, then a slow full turn to show the head all round. */
    static final Clip MACE = new Clip(2.8f, BLADE, GRIP,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.18f, -0.10f, 0.06f, 0.06f, -6, -30, 10, 0, 0, 0),
        snd(k(0.34f, -0.12f, 0.02f, 0.06f, 2, -34, 12, 0, 0, 0), SND_CLOTH),
        k(0.44f, -0.12f, 0.07f, 0.06f, -6, -34, 12, 0, 0, 0),
        k(0.72f, -0.12f, 0.07f, 0.06f, -6, -34, 12, 0, 0, 360),
        k(0.88f, 0, -0.03f, 0, 4, 0, 0, 0, 0, 360),
        k(1f, 0, 0, 0, 0, 0, 0, 0, 0, 360));

    /** Tridents and spears: spun like a baton about the grip (hand layer), caught, rolled over, lowered. */
    static final Clip STAFF = new Clip(2.4f, BLADE, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.14f, -0.14f, 0.10f, 0.02f, -4, -8, 10, 0, 0, 0),
        snd(k(0.30f, -0.18f, 0.12f, 0.02f, -4, -6, -350, 0, 0, 0), SND_WHOOSH),
        snd(k(0.46f, -0.18f, 0.12f, 0.02f, -4, -6, -710, 0, 0, 0), SND_WHOOSH),
        snd(stop(k(0.56f, -0.18f, 0.12f, 0.02f, -4, -6, -720, 0, 0, 0)), SND_CATCH),
        k(0.74f, -0.12f, 0.10f, 0.04f, -20, -30, -710, 180, 0, 0),
        k(0.90f, 0, -0.02f, 0, 3, 0, -720, 360, 0, 0),
        k(1f, 0, 0, 0, 0, 0, -720, 360, 0, 0));

    /** Bow: turned to the string side, tilted to look along it, turned back. */
    static final Clip BOW = new Clip(2.2f, BLADE, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.15f, -0.10f, 0.06f, 0.05f, -6, -20, 10, 0, 0, 0),
        snd(k(0.36f, -0.12f, 0.08f, 0.06f, -10, -30, 14, 0, 0, 180), SND_CLOTH),
        k(0.52f, -0.12f, 0.08f, 0.06f, -10, -30, 30, 0, 0, 180),
        k(0.70f, -0.10f, 0.10f, 0.05f, -18, -20, 8, 0, 0, 180),
        k(0.88f, 0, -0.02f, 0, 3, 0, 0, 0, 0, 360),
        k(1f, 0, 0, 0, 0, 0, 0, 0, 0, 360));

    /** Crossbow: brought up to look down the sights, then tilted to check the bolt. */
    static final Clip CROSSBOW = new Clip(2.4f, BLADE, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.16f, -0.25f, 0.12f, 0.08f, -4, -20, 6, 0, 0, 0),
        snd(stop(k(0.34f, -0.44f, 0.20f, 0.10f, 2, -8, 0, 0, 0, 0)), SND_CLOTH),
        k(0.48f, -0.44f, 0.21f, 0.10f, 2, -8, 0, 0, 0, 0),
        k(0.66f, -0.26f, 0.14f, 0.08f, -8, -30, 35, 0, 0, 0),
        k(0.90f, 0, -0.02f, 0, 3, 0, 0, 0, 0, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    /** Blocks: turned a quarter at a time like a puzzle cube, tossed up tumbling, caught, squared up. */
    static final Clip BLOCK = new Clip(2.4f, X, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.14f, -0.16f, 0.16f, 0.04f, 0, -10, 0, 0, 0, 0),
        snd(stop(k(0.28f, -0.16f, 0.17f, 0.04f, 0, -10, 0, 0, 0, 90)), SND_CATCH),
        snd(stop(k(0.40f, -0.16f, 0.17f, 0.04f, 0, -10, 0, 90, 0, 90)), SND_CATCH),
        snd(stop(k(0.52f, -0.16f, 0.17f, 0.04f, 0, -10, 0, 90, 0, 180)), SND_CATCH),
        k(0.62f, -0.16f, 0.34f, 0.04f, 0, -10, 0, 180, 0, 180),
        snd(stop(k(0.74f, -0.16f, 0.14f, 0.04f, 0, -10, 0, 360, 0, 270)), SND_CATCH),
        k(0.88f, 0, -0.02f, 0, 2, 0, 0, 360, 0, 360),
        k(1f, 0, 0, 0, 0, 0, 0, 360, 0, 360));

    /** Totem: raised to the middle of the screen and turned all the way round. */
    static final Clip SHOWCASE = new Clip(2.6f, Y, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.16f, -0.22f, 0.14f, 0.10f, -6, -10, 0, 0, 0, 0),
        k(0.30f, -0.26f, 0.18f, 0.12f, -4, 0, 0, 0, 0, 0),
        k(0.72f, -0.26f, 0.20f, 0.12f, -4, 0, 0, 0, 0, 360),
        k(0.90f, 0, -0.02f, 0, 3, 0, 0, 0, 0, 360),
        k(1f, 0, 0, 0, 0, 0, 0, 0, 0, 360));

    /** Food and potions: brought closer, turned round, a small coin-flip toss and catch. */
    static final Clip CONSUMABLE = new Clip(2.2f, X, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.16f, -0.14f, 0.08f, 0.10f, -8, -20, 6, 0, 0, 0),
        k(0.36f, -0.16f, 0.10f, 0.12f, -8, -24, 6, 0, 0, 180),
        stop(k(0.48f, -0.16f, 0.10f, 0.12f, -8, -24, 6, 0, 0, 180)),
        snd(k(0.60f, -0.16f, 0.22f, 0.12f, -8, -24, 6, 180, 0, 180), SND_WHOOSH),
        snd(stop(k(0.70f, -0.16f, 0.07f, 0.12f, -6, -24, 6, 360, 0, 180)), SND_CATCH),
        k(0.88f, 0, -0.02f, 0, 3, 0, 0, 360, 0, 360),
        k(1f, 0, 0, 0, 0, 0, 0, 360, 0, 360));

    /** Pearls, snowballs, eggs, charges: a dip, tossed up flipping twice, caught with a little give. */
    static final Clip TOSS = new Clip(1.8f, X, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.12f, -0.06f, 0.02f, 0.04f, 4, -10, 4, 0, 0, 0),
        snd(k(0.40f, -0.08f, 0.30f, 0.04f, -10, -10, 4, 360, 0, 0), SND_WHOOSH),
        snd(stop(k(0.56f, -0.08f, 0.04f, 0.04f, 4, -10, 4, 720, 0, 0)), SND_CATCH),
        k(0.66f, -0.08f, 0.08f, 0.04f, -2, -10, 4, 720, 0, 0),
        k(0.90f, 0, -0.02f, 0, 2, 0, 0, 720, 0, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 720, 0, 0));

    /** Empty hand: the arm turns to show the back of the hand, flexes, and drops back. */
    static final Clip HAND = new Clip(2.0f, Y, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.18f, -0.10f, 0.14f, 0.02f, 10, -20, 20, 0, 0, 0),
        k(0.40f, -0.16f, 0.20f, 0, 15, -45, 35, 0, 0, 0),
        k(0.52f, -0.16f, 0.20f, 0, 12, -45, 45, 0, 0, 0),
        k(0.62f, -0.16f, 0.20f, 0, 15, -45, 30, 0, 0, 0),
        k(0.82f, -0.05f, 0.05f, 0, 4, -10, 6, 0, 0, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    /** Maps: brought up closer and tilted toward you. */
    static final Clip MAP = new Clip(2.0f, Y, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.20f, 0, 0.06f, 0.12f, -10, 0, 0, 0, 0, 0),
        k(0.60f, 0, 0.07f, 0.12f, -12, 0, 4, 0, 0, 0),
        k(0.85f, 0, -0.01f, 0, 2, 0, 0, 0, 0, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    /** Anything else: flipped like a card to show the back, tilted, flipped home. */
    static final Clip GENERIC = new Clip(2.2f, Y, CENTRE,
        k(0f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        k(0.16f, -0.10f, 0.08f, 0.06f, -8, -20, 10, 0, 0, 0),
        snd(k(0.40f, -0.12f, 0.10f, 0.07f, -10, -26, 12, 180, 0, 0), SND_WHOOSH),
        stop(k(0.54f, -0.12f, 0.10f, 0.07f, -10, -26, 12, 180, 0, 0)),
        k(0.74f, -0.08f, 0.08f, 0.04f, -14, -10, 6, 180, 0, 0),
        k(0.90f, 0, -0.02f, 0, 3, 0, 0, 360, 0, 0),
        k(1f, 0, 0, 0, 0, 0, 0, 360, 0, 0));

    /** Every clip, for the self-check. */
    public static final java.util.Map<String, Clip> CLIPS = java.util.Map.ofEntries(
        java.util.Map.entry("sword_flip", SWORD_FLIP), java.util.Map.entry("sword_twirl", SWORD_TWIRL),
        java.util.Map.entry("axe", AXE), java.util.Map.entry("tool", TOOL), java.util.Map.entry("mace", MACE),
        java.util.Map.entry("staff", STAFF), java.util.Map.entry("bow", BOW), java.util.Map.entry("crossbow", CROSSBOW),
        java.util.Map.entry("block", BLOCK), java.util.Map.entry("showcase", SHOWCASE), java.util.Map.entry("consumable", CONSUMABLE),
        java.util.Map.entry("toss", TOSS), java.util.Map.entry("hand", HAND), java.util.Map.entry("map", MAP), java.util.Map.entry("generic", GENERIC));

    private Clip pick(ItemStack s) {
        switch (style.get()) {
            case "Flip" -> { return s.isEmpty() ? HAND : SWORD_FLIP; }
            case "Twirl" -> { return s.isEmpty() ? HAND : SWORD_TWIRL; }
            case "Toss" -> { return s.isEmpty() ? HAND : TOSS; }
            case "Showcase" -> { return s.isEmpty() ? HAND : SHOWCASE; }
            default -> {}
        }
        if (s.isEmpty()) return HAND;
        if (s.has(DataComponents.MAP_ID)) return MAP;
        if (s.is(ItemTags.SWORDS)) return (swordVariant++ & 1) == 0 ? SWORD_FLIP : SWORD_TWIRL;
        if (s.is(ItemTags.AXES)) return AXE;
        if (s.is(ItemTags.PICKAXES) || s.is(ItemTags.SHOVELS) || s.is(ItemTags.HOES)) return TOOL;
        if (s.is(Items.MACE)) return MACE;
        if (s.is(Items.TRIDENT) || s.is(ItemTags.SPEARS)) return STAFF;
        if (s.is(Items.BOW)) return BOW;
        if (s.is(Items.CROSSBOW)) return CROSSBOW;
        if (s.is(Items.TOTEM_OF_UNDYING)) return SHOWCASE;
        if (s.is(Items.ENDER_PEARL) || s.is(Items.SNOWBALL) || s.is(ItemTags.EGGS) || s.is(Items.WIND_CHARGE) || s.is(Items.FIRE_CHARGE)
            || s.is(Items.EXPERIENCE_BOTTLE) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) return TOSS;
        if (s.has(DataComponents.CONSUMABLE)) return CONSUMABLE;
        if (s.getItem() instanceof BlockItem) return BLOCK;
        return GENERIC;
    }

    /**
     * Dev self-check: every clip starts at rest, ends on rest (angles on multiples of 360), and never jumps between
     * two samples 1 ms apart. Returns a list of problems (empty = fine).
     */
    public static java.util.List<String> selfCheck() {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (var e : CLIPS.entrySet()) {
            Clip c = e.getValue();
            float[] first = c.sample(0), last = c.sample(1);
            for (int ch = 0; ch < N; ch++) {
                if (Math.abs(first[ch]) > 1e-4) bad.add(e.getKey() + " starts off rest on channel " + ch);
                float end = ch >= RX ? Mth.wrapDegrees(last[ch]) : last[ch];
                if (Math.abs(end) > 1e-3) bad.add(e.getKey() + " ends off rest on channel " + ch + " (" + last[ch] + ")");
            }
            float step = 0.001f / c.seconds; // 1 ms of playback
            float[] prev = first;
            for (float u = step; u <= 1; u += step) {
                float[] p = c.sample(u);
                for (int ch = 0; ch < N; ch++) {
                    float limit = ch < RX ? 0.01f : 3f; // per millisecond: 10 blocks/s, 3000 deg/s
                    if (!Float.isFinite(p[ch]) || Math.abs(p[ch] - prev[ch]) > limit) {
                        bad.add(String.format(java.util.Locale.ROOT, "%s jumps on channel %d at u=%.3f", e.getKey(), ch, u));
                        break;
                    }
                }
                prev = p;
            }
            for (int i = 1; i < c.keys.length; i++) if (c.keys[i].t <= c.keys[i - 1].t) bad.add(e.getKey() + " keys out of order at " + i);
        }
        return bad;
    }
}

package dev.prismglass.manager;

import net.minecraft.util.Mth;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Mirrors Grim's PacketOrderProcessor on our own outgoing traffic, so modules can ask
 * "would sending X now break packet order?" before they act, instead of finding out from a flag.
 *
 * <p>State is reset on every tick packet (movement packet or ClientTickEnd), exactly like Grim.
 */
public final class PacketGuard {
    private final Minecraft mc = Minecraft.getInstance();

    // actions since the last tick packet
    private boolean attacked, interacted, placed, used, digging, releasing, sprintAction, sneakAction;
    private int places;
    private int lastEntityId = Integer.MIN_VALUE;
    /** True between this tick's movement packet and the tick-end packet (PacketOrderO window). */
    private boolean afterFlying;
    /** Slot the server currently has selected. */
    private int serverSlot = -1;
    /** Slot change deferred to the next tick because it would have broken order this tick. */
    private int pendingSlot = -1;
    private boolean lastShift;

    /** Called for every outgoing packet before it is sent. May cancel it. */
    public void onSend(PacketEvent event) {
        Packet<?> p = event.packet;
        Prism.invGuard().onSend(event);
        boolean strict = Prism.anticheat().packetOrder.get();

        if (p instanceof ServerboundSetCarriedItemPacket slot) {
            // BadPacketsA: never send the slot the server already has.
            if (strict && slot.getSlot() == serverSlot) { event.cancel(); return; }
            serverSlot = slot.getSlot();
            return;
        }

        if (p instanceof ServerboundMovePlayerPacket) {
            resetActions();
            afterFlying = true;
            return;
        }
        if (p instanceof ServerboundClientTickEndPacket) {
            if (!afterFlying) resetActions(); // 1.21.2+: tick end counts as the tick packet when no movement was sent
            afterFlying = false;
            return;
        }

        if (p instanceof ServerboundAttackPacket attack) {
            // 26.1: attacks have their own packet (Grim's ATTACK)
            attacked = true;
            lastEntityId = attack.entityId();
        } else if (p instanceof ServerboundInteractPacket entity) {
            interacted = true;
            lastEntityId = entity.entityId();
        } else if (p instanceof ServerboundUseItemOnPacket) {
            placed = true;
            places++;
        } else if (p instanceof ServerboundUseItemPacket use) {
            used = true;
            fixUseRotation(use);
        } else if (p instanceof ServerboundPlayerActionPacket action) {
            switch (action.getAction()) {
                case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> digging = true;
                case RELEASE_USE_ITEM -> releasing = true;
                default -> {}
            }
        } else if (p instanceof ServerboundPlayerCommandPacket cmd) {
            switch (cmd.getAction()) {
                case START_SPRINTING, STOP_SPRINTING -> sprintAction = true;
                default -> {}
            }
        } else if (p instanceof ServerboundPlayerInputPacket input) {
            // 26.1: sneaking is part of the input packet; a change of shift counts as the sneak action
            if (input.input().shift() != lastShift) sneakAction = true;
            lastShift = input.input().shift();
        }
    }

    /** BadPacketsJ: the use packet's rotation must equal the movement packet's rotation this tick. */
    private void fixUseRotation(ServerboundUseItemPacket use) {
        RotationManager rot = Prism.rotations();
        if (!rot.isActive() || !Prism.anticheat().fixUseRotation.get()) return;
        use.yRot = rot.getYaw(); // continuous with the movement packets (re-wrapping around the camera can flip by 360)
        use.xRot = rot.getPitch();
    }

    private void resetActions() {
        attacked = interacted = placed = used = digging = releasing = sprintAction = sneakAction = false;
        places = 0;
        lastEntityId = Integer.MIN_VALUE;
    }

    /** Start of client tick: flush deferred slot restores first, so they precede any action. */
    public void onTickStart() {
        if (pendingSlot != -1 && mc.player != null) {
            int slot = pendingSlot;
            pendingSlot = -1;
            if (mc.player.getInventory().getSelectedSlot() != slot) mc.player.getInventory().setSelectedSlot(slot);
            mc.gameMode.ensureHasSentCarriedItem();
        }
    }

    public void onDisconnect() {
        resetActions();
        serverSlot = -1;
        pendingSlot = -1;
        afterFlying = false;
    }

    // ---- queries used by modules ----------------------------------------------------------

    private boolean strict() { return Prism.anticheat().packetOrder.get(); }

    /** PacketOrderE: a slot change after attack/place/use/sprint/sneak in the same tick flags. */
    public boolean canSwitchSlot() {
        return !strict() || !(attacked || interacted || placed || used || releasing || sprintAction || sneakAction);
    }

    /** Queue a slot to be selected at the start of next tick (used for swap-backs). */
    public void deferSlot(int slot) { pendingSlot = slot; }

    /** MultiInteractA + PacketOrderO. */
    public boolean canInteractEntity(int entityId) {
        if (!strict()) return true;
        if (afterFlying) return false;
        if (Prism.anticheat().oneTargetPerTick.get() && (attacked || interacted) && entityId != lastEntityId) return false;
        return !digging && !releasing; // PacketOrderI
    }

    /** MultiPlace + PacketOrderI/O. */
    public boolean canPlace() {
        if (!strict()) return true;
        if (afterFlying || digging || releasing) return false;
        return places < Prism.anticheat().placesPerTick.getInt();
    }

    public boolean hasAttacked() { return attacked; }
    /** A use-item packet already went out this tick. */
    public boolean hasUsed() { return used; }
    public boolean isAfterFlying() { return afterFlying; }
}

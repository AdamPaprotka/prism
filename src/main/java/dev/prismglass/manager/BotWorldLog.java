package dev.prismglass.manager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * Records just enough of the clientbound play stream for one bot to rebuild its world on a seamless switch, kept small
 * the way we designed it:
 * <ul>
 *   <li><b>Reset on baseline.</b> A login or respawn packet is the new baseline: everything older is dropped and that
 *       packet is kept to replay first (it is what makes the world and the player).</li>
 *   <li><b>Latest per key, delete on unload.</b> One chunk packet per (x,z), one light packet per (x,z), dropped when
 *       the server forgets that chunk. So the chunk store is bounded by view distance, not by time.</li>
 *   <li><b>Latest per entity, delete on remove.</b> The spawn packet plus the latest data / equipment / position per
 *       entity id, dropped when the entity leaves.</li>
 *   <li><b>Latest per self packet.</b> One of each of health, xp, abilities, held slot, inventory, position.</li>
 *   <li><b>Everything else is dropped</b> (sounds, particles, animations, chat, block-break progress...): none of it
 *       is world state to rebuild.</li>
 * </ul>
 * This is only recorded; {@link BotPlayers} replays it into a real client listener on a fast switch. The replay side
 * is experimental and meant to be debugged against a live server.
 */
final class BotWorldLog {
    /** A login / respawn packet: the baseline the world is rebuilt from. */
    private Packet<? super ClientGamePacketListener> baseline;
    /** chunk (x,z) packed -> its chunk-with-light packet. */
    private final Map<Long, ClientboundLevelChunkWithLightPacket> chunks = new LinkedHashMap<>();
    private final Map<Long, ClientboundLightUpdatePacket> light = new LinkedHashMap<>();
    /** entity id -> {spawn, latest-data, latest-equipment, latest-position}. */
    private final Map<Integer, Entity> entities = new LinkedHashMap<>();
    /** latest "self" state packets by their class. */
    private final Map<Class<?>, Packet<? super ClientGamePacketListener>> self = new LinkedHashMap<>();
    /** recent block / section updates on top of the chunks (bounded). */
    private final Deque<Packet<? super ClientGamePacketListener>> blockEdits = new ArrayDeque<>();
    /** tab-list updates, in order (bounded). */
    private final Deque<ClientboundPlayerInfoUpdatePacket> tab = new ArrayDeque<>();
    private int center; // ClientboundSetChunkCacheCenterPacket, kept latest
    private ClientboundSetChunkCacheCenterPacket centerPacket;

    private static final int MAX_BLOCK_EDITS = 4096;
    private static final int MAX_TAB = 256;

    private static final class Entity {
        ClientboundAddEntityPacket spawn;
        Packet<? super ClientGamePacketListener> data, equipment, position;
    }

    void clear() {
        baseline = null; chunks.clear(); light.clear(); entities.clear(); self.clear();
        blockEdits.clear(); tab.clear(); centerPacket = null;
    }

    /** Packets kept right now (dev / introspection). */
    int size() { return (baseline != null ? 1 : 0) + chunks.size() + light.size() + entities.size() * 2 + self.size() + blockEdits.size() + tab.size(); }

    @SuppressWarnings("unchecked")
    void record(Packet<?> raw) {
        Packet<? super ClientGamePacketListener> p = (Packet<? super ClientGamePacketListener>) raw;
        if (p instanceof ClientboundLoginPacket || p instanceof ClientboundRespawnPacket) {
            clear();
            baseline = p;
            return;
        }
        if (baseline == null) return; // nothing to attach to yet
        switch (p) {
            case ClientboundLevelChunkWithLightPacket c -> chunks.put(ChunkPos.pack(c.x(), c.z()), c);
            case ClientboundLightUpdatePacket l -> light.put(ChunkPos.pack(l.x(), l.z()), l);
            case ClientboundForgetLevelChunkPacket f -> { long k = f.pos().pack(); chunks.remove(k); light.remove(k); }
            case ClientboundSetChunkCacheCenterPacket cc -> centerPacket = cc;
            case ClientboundBlockUpdatePacket b -> addEdit(p);
            case ClientboundSectionBlocksUpdatePacket b -> addEdit(p);
            case ClientboundAddEntityPacket e -> entities.computeIfAbsent(e.getId(), i -> new Entity()).spawn = e;
            case ClientboundSetEntityDataPacket e -> withEntity(e.id(), en -> en.data = p);
            case ClientboundSetEquipmentPacket e -> withEntity(e.getEntity(), en -> en.equipment = p);
            case ClientboundTeleportEntityPacket e -> withEntity(e.id(), en -> en.position = p);
            case ClientboundEntityPositionSyncPacket e -> withEntity(e.id(), en -> en.position = p);
            case ClientboundSetEntityMotionPacket e -> { /* velocity: not world state, drop */ }
            case ClientboundRemoveEntitiesPacket r -> r.entityIds().forEach((int id) -> entities.remove(id));
            case ClientboundPlayerInfoUpdatePacket t -> { tab.addLast(t); while (tab.size() > MAX_TAB) tab.removeFirst(); }
            case ClientboundSetHealthPacket s -> self.put(p.getClass(), p);
            case ClientboundSetExperiencePacket s -> self.put(p.getClass(), p);
            case ClientboundPlayerAbilitiesPacket s -> self.put(p.getClass(), p);
            case ClientboundSetHeldSlotPacket s -> self.put(p.getClass(), p);
            case ClientboundContainerSetContentPacket s -> { if (s.containerId() == 0) self.put(p.getClass(), p); }
            case ClientboundPlayerPositionPacket s -> self.put(p.getClass(), p);
            case ClientboundUpdateAttributesPacket s -> self.put(p.getClass(), p);
            default -> { /* ephemeral: dropped */ }
        }
    }

    private void addEdit(Packet<? super ClientGamePacketListener> p) {
        blockEdits.addLast(p);
        while (blockEdits.size() > MAX_BLOCK_EDITS) blockEdits.removeFirst();
    }

    private void withEntity(int id, java.util.function.Consumer<Entity> c) {
        Entity e = entities.get(id);
        if (e != null) c.accept(e);
    }

    /** True once a world baseline (login/respawn) has been seen: a fast switch is possible. */
    boolean ready() { return baseline != null && !chunks.isEmpty(); }

    /**
     * The recorded packets in an order a fresh client listener accepts: baseline, self state, chunk centre, chunks,
     * light, block edits, entities (spawn then data/equipment/position), tab list.
     */
    List<Packet<? super ClientGamePacketListener>> replayOrder() {
        List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
        if (baseline != null) out.add(baseline);
        out.addAll(self.values());
        if (centerPacket != null) out.add(centerPacket);
        out.addAll(chunks.values());
        out.addAll(light.values());
        out.addAll(blockEdits);
        for (Entity e : entities.values()) if (e.spawn != null) {
            out.add(e.spawn);
            if (e.data != null) out.add(e.data);
            if (e.equipment != null) out.add(e.equipment);
            if (e.position != null) out.add(e.position);
        }
        out.addAll(tab);
        return out;
    }
}

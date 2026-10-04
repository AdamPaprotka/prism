package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.world.ElytraFinder;
import dev.prismglass.xray.StructurePredictor;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.AABB;

/**
 * Dev-only ElytraFinder test (./gradlew runClient -Pelytratest): predicted end ships/elytras vs the integrated server's
 * real end city pieces, a real item frame check at one predicted elytra, then the module in the End.
 */
public final class ElytraTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final long SEED = 1234567890123L;
    private static int ticks, t;
    private static boolean requested;
    private static BlockPos target;

    private ElytraTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[elytratest] {}", s); }

    private static void run(MinecraftServer server, String cmd) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    private static void tick() {
        ticks++;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            mc.options.pauseOnLostFocus = false;
            String name = "prismelytra-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SPECTATOR, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(SEED, true, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        t++;
        MinecraftServer server = mc.getSingleplayerServer();
        if (t == 40) {
            long t0 = System.nanoTime();
            StructurePredictor p = StructurePredictor.create(Level.END, SEED);
            int cx = 100, cz = 100, r = 64;
            List<StructurePredictor.Hit> cities = p.scan(cx, cz, r, c -> c.equals("end_city"));
            List<StructurePredictor.Ship> ships = p.endShips(cx, cz, r);
            log(String.format("predicted %d end cities, %d ships in %.0f ms", cities.size(), ships.size(), (System.nanoTime() - t0) / 1e6));
            Map<BlockPos, StructurePredictor.Ship> predictedByCity = new HashMap<>();
            for (var s : ships) predictedByCity.put(s.city(), s);

            // server truth: the real end city start at each predicted city chunk
            int[] stats = server.submit(() -> {
                var level = server.getLevel(Level.END);
                var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.END_CITY);
                int realCities = 0, realShips = 0, shipMatch = 0, elytraMatch = 0, falseShips = 0;
                for (var city : cities) {
                    var chunk = level.getChunk(city.chunk().x(), city.chunk().z(), ChunkStatus.STRUCTURE_STARTS, true);
                    var start = chunk.getStartForStructure(structure);
                    StructurePredictor.Ship predicted = predictedByCity.get(city.pos());
                    if (start == null || !start.isValid()) { if (predicted != null) falseShips++; continue; }
                    realCities++;
                    StructurePredictor.Ship real = StructurePredictor.shipOf(city.pos(), start.getPieces());
                    if (real == null) { if (predicted != null) falseShips++; continue; }
                    realShips++;
                    if (predicted != null) {
                        shipMatch++;
                        if (Objects.equals(predicted.elytra(), real.elytra())) elytraMatch++;
                        else log("elytra mismatch: predicted " + predicted.elytra() + " real " + real.elytra());
                    }
                }
                return new int[]{realCities, realShips, shipMatch, elytraMatch, falseShips};
            }).join();
            log(String.format("server: %d real cities, %d real ships; ships predicted %d/%d, elytra exact %d/%d, false ships %d",
                stats[0], stats[1], stats[2], stats[1], stats[3], stats[1], stats[4]));

            target = ships.stream().map(StructurePredictor.Ship::elytra).filter(Objects::nonNull).findFirst().orElse(null);
            if (target != null) {
                log("checking elytra at " + target);
                run(server, "execute in minecraft:the_end run forceload add " + target.getX() + " " + target.getZ());
            }
        }
        if (t == 200 && target != null) {
            List<String> items = server.submit(() -> {
                var level = server.getLevel(Level.END);
                List<String> out = new ArrayList<>();
                for (var f : level.getEntities(EntityType.ITEM_FRAME, new AABB(target).inflate(1.5), e -> true)) {
                    out.add(f.blockPosition() + "=" + f.getItem().getItem());
                }
                return out;
            }).join();
            log("server item frames at predicted elytra: " + items);
            Prism.modules().get(dev.prismglass.module.render.Xray.class).seed.set(String.valueOf(SEED));
            Prism.modules().get(ElytraFinder.class).setEnabled(true);
            run(server, "execute in minecraft:the_end run tp @p " + (target.getX() + 14) + " " + (target.getY() + 8) + " " + (target.getZ() + 14)
                + " facing " + target.getX() + " " + target.getY() + " " + target.getZ());
        }
        if (t == 500) {
            log("module: " + Prism.modules().get(ElytraFinder.class).getInfo());
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-elytra.png", mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        }
        if (t == 520) {
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }
}

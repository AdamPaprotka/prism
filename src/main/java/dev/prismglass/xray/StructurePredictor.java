package dev.prismglass.xray;

import dev.prismglass.Prism;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;

/**
 * Predicts structure starts from the world seed, client-side, the way {@code ChunkGenerator#createStructures} does:
 * the dimension's biome source, noise and structure-set placement are rebuilt from the built-in registries, each set's
 * placement chunks are enumerated ({@code getPotentialStructureChunk} / stronghold rings) and filtered with vanilla's
 * {@code isStructureChunk} (frequency, exclusion zones), multi-structure sets pick with vanilla's weighted
 * {@code setLargeFeatureSeed} loop, and each structure is validated with its own {@code findValidGenerationPoint}
 * (biome, terrain height...).
 *
 * <p>Jigsaw structures (villages, outposts, bastions, ancient cities, trail ruins, trial chambers) and ruined portals
 * need the server's structure templates to place their start piece; for those the start height and biome check are
 * reproduced at the chunk centre instead, which can differ right at biome borders.
 */
public final class StructurePredictor {
    public record Hit(String category, String id, ChunkPos chunk, BlockPos pos) {}

    /** An end city's ship: where the city starts, the ship's centre, and the Elytra item frame (null if no marker). */
    public record Ship(BlockPos city, BlockPos ship, BlockPos elytra) {}

    private static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager endTemplates;

    private static HolderLookup.Provider lookup;

    private final long seed;
    private final ResourceKey<Level> dim;
    private final NoiseBasedChunkGenerator generator;
    private final BiomeSource source;
    private final RandomState randomState;
    private final ChunkGeneratorStructureState state;
    private final LevelHeightAccessor height;

    private StructurePredictor(ResourceKey<Level> dim, long seed) {
        this.seed = seed;
        this.dim = dim;
        if (lookup == null) {
            lookup = VanillaRegistries.createLookup();
            bindBiomeTags(lookup);
        }
        ResourceKey<NoiseGeneratorSettings> settingsKey;
        if (dim == Level.NETHER) {
            source = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
            settingsKey = NoiseGeneratorSettings.NETHER;
            height = LevelHeightAccessor.create(0, 256);
        } else if (dim == Level.END) {
            source = TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME));
            settingsKey = NoiseGeneratorSettings.END;
            height = LevelHeightAccessor.create(0, 256);
        } else {
            source = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            settingsKey = NoiseGeneratorSettings.OVERWORLD;
            height = LevelHeightAccessor.create(-64, 384);
        }
        Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
        generator = new NoiseBasedChunkGenerator(source, settings);
        randomState = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), seed);
        // createForNormal would list tag contents (unbound here); same state, sets filtered with contains() instead
        Set<Holder<Biome>> possible = source.possibleBiomes();
        List<Holder<StructureSet>> sets = lookup.lookupOrThrow(Registries.STRUCTURE_SET).listElements()
            .<Holder<StructureSet>>map(h -> h)
            .filter(set -> set.value().structures().stream().anyMatch(e -> possible.stream().anyMatch(e.structure().value().biomes()::contains)))
            .toList();
        state = new ChunkGeneratorStructureState(randomState, source, seed, seed, sets);
        state.ensureStructuresGenerated();
    }

    /** Null for dimensions without vanilla generation. */
    public static StructurePredictor create(ResourceKey<Level> dim, long seed) {
        if (dim != Level.OVERWORLD && dim != Level.NETHER && dim != Level.END) return null;
        try {
            return new StructurePredictor(dim, seed);
        } catch (Throwable t) {
            Prism.LOG.error("[structures] could not build the predictor", t);
            return null;
        }
    }

    public boolean isFor(ResourceKey<Level> d, long s) { return d == dim && s == seed; }

    /** Every predicted structure start within {@code radius} chunks (square) of the given chunk. */
    public List<Hit> scan(int cx, int cz, int radius, Predicate<String> wantCategory) {
        List<Hit> out = new ArrayList<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            StructureSet value = set.value();
            if (value.structures().stream().noneMatch(e -> wantCategory.test(category(id(e.structure()))))) continue;
            StructurePlacement placement = value.placement();
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                int spacing = spread.spacing();
                int rx0 = Math.floorDiv(cx - radius, spacing), rx1 = Math.floorDiv(cx + radius, spacing);
                int rz0 = Math.floorDiv(cz - radius, spacing), rz1 = Math.floorDiv(cz + radius, spacing);
                for (int rx = rx0; rx <= rx1; rx++) for (int rz = rz0; rz <= rz1; rz++) {
                    ChunkPos c = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (Math.abs(c.x() - cx) > radius || Math.abs(c.z() - cz) > radius) continue;
                    if (!placement.isStructureChunk(state, c.x(), c.z())) continue;
                    Hit h = resolve(value, c);
                    if (h != null && wantCategory.test(h.category())) out.add(h);
                }
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions == null) continue;
                for (ChunkPos c : positions) {
                    if (Math.abs(c.x() - cx) > radius || Math.abs(c.z() - cz) > radius) continue;
                    Hit h = resolve(value, c);
                    if (h != null && wantCategory.test(h.category())) out.add(h);
                }
            }
        }
        return out;
    }

    /** Vanilla's choice for a placement chunk: single structure, or weighted retry loop over the set. */
    private Hit resolve(StructureSet set, ChunkPos chunk) {
        List<StructureSet.StructureSelectionEntry> entries = set.structures();
        if (entries.size() == 1) return tryStructure(entries.get(0), chunk);
        List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(entries);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
        int total = 0;
        for (StructureSet.StructureSelectionEntry option : options) total += option.weight();
        while (!options.isEmpty()) {
            int choice = random.nextInt(total);
            int index = 0;
            for (StructureSet.StructureSelectionEntry option : options) {
                choice -= option.weight();
                if (choice < 0) break;
                index++;
            }
            StructureSet.StructureSelectionEntry selected = options.get(index);
            Hit h = tryStructure(selected, chunk);
            if (h != null) return h;
            options.remove(index);
            total -= selected.weight();
        }
        return null;
    }

    private Hit tryStructure(StructureSet.StructureSelectionEntry entry, ChunkPos chunk) {
        Structure structure = entry.structure().value();
        Predicate<Holder<Biome>> biomeOk = structure.biomes()::contains;
        String id = id(entry.structure());
        try {
            Structure.GenerationContext ctx = new Structure.GenerationContext(
                null, generator, source, randomState, null, seed, chunk, height, biomeOk);
            BlockPos pos;
            if (structure instanceof JigsawStructure jigsaw) {
                int startY = jigsaw.startHeight.sample(ctx.random(), new WorldGenerationContext(generator, height));
                int x = chunk.getMinBlockX() + 8, z = chunk.getMinBlockZ() + 8;
                Optional<Heightmap.Types> project = jigsaw.projectStartToHeightmap;
                int y = project.isEmpty() ? startY : startY + generator.getFirstFreeHeight(x, z, project.get(), height, randomState);
                pos = new BlockPos(x, y, z);
                if (!biomeOk.test(biomeAt(pos))) return null;
            } else if (structure instanceof RuinedPortalStructure) {
                int x = chunk.getMinBlockX() + 8, z = chunk.getMinBlockZ() + 8;
                pos = new BlockPos(x, generator.getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, randomState), z);
                if (!biomeOk.test(biomeAt(pos))) return null;
            } else {
                Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(ctx);
                if (stub.isEmpty()) return null;
                pos = stub.get().position();
            }
            return new Hit(category(id), id, chunk, pos);
        } catch (Throwable t) {
            Prism.LOG.debug("[structures] {} at {} failed", id, chunk, t);
            return null;
        }
    }

    /**
     * The built-in lookup has no tags (they come from data packs). Biome tags drive every structure biome check
     * (has_structure/..., stronghold_biased_to, required_ocean_monument_surrounding), so load vanilla's from the game
     * jar and bind them onto the biome holders; membership checks (contains / is) then work.
     */
    private static void bindBiomeTags(HolderLookup.Provider provider) {
        Map<String, List<String>> raw = new HashMap<>();
        try (var pack = net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource()) {
            pack.listResources(net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft", "tags/worldgen/biome", (id, supplier) -> {
                String path = id.getPath();
                String name = path.substring("tags/worldgen/biome/".length(), path.length() - ".json".length());
                try (var in = new java.io.InputStreamReader(supplier.get(), java.nio.charset.StandardCharsets.UTF_8)) {
                    var json = com.google.gson.JsonParser.parseReader(in).getAsJsonObject();
                    List<String> values = new ArrayList<>();
                    for (var v : json.getAsJsonArray("values")) {
                        values.add(v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString());
                    }
                    raw.put("minecraft:" + name, values);
                } catch (Exception e) {
                    Prism.LOG.warn("[structures] bad biome tag {}", id, e);
                }
            });
        }
        // resolve nested #tags into biome ids, then invert: biome -> tags
        Map<String, Set<String>> resolved = new HashMap<>();
        for (String tag : raw.keySet()) resolveTag(tag, raw, resolved, new HashSet<>());
        Map<String, List<net.minecraft.tags.TagKey<Biome>>> byBiome = new HashMap<>();
        for (var e : resolved.entrySet()) {
            var key = net.minecraft.tags.TagKey.create(Registries.BIOME, net.minecraft.resources.Identifier.parse(e.getKey()));
            for (String biome : e.getValue()) byBiome.computeIfAbsent(biome, k -> new ArrayList<>()).add(key);
        }
        var biomes = provider.lookupOrThrow(Registries.BIOME);
        biomes.listElements().forEach(holder ->
            holder.bindTags(byBiome.getOrDefault(holder.key().identifier().toString(), List.of())));
        // vanilla also *lists* each structure's biome tag (stronghold ring setup), which unbound tag sets can't do:
        // these Structure objects are this lookup's own copies, so give them the resolved biome list directly
        provider.lookupOrThrow(Registries.STRUCTURE).listElements().forEach(holder -> {
            Structure structure = holder.value();
            var tag = structure.biomes().unwrapKey();
            if (tag.isEmpty()) return;
            List<Holder<Biome>> members = new ArrayList<>();
            for (String id : resolved.getOrDefault(tag.get().location().toString(), Set.of())) {
                biomes.get(ResourceKey.create(Registries.BIOME, net.minecraft.resources.Identifier.parse(id))).ifPresent(members::add);
            }
            Structure.StructureSettings old = structure.settings;
            structure.settings = new Structure.StructureSettings(net.minecraft.core.HolderSet.direct(members),
                old.spawnOverrides(), old.step(), old.terrainAdaptation());
        });
        Prism.LOG.info("[structures] bound {} vanilla biome tags", resolved.size());
    }

    private static Set<String> resolveTag(String tag, Map<String, List<String>> raw, Map<String, Set<String>> done, Set<String> visiting) {
        Set<String> cached = done.get(tag);
        if (cached != null) return cached;
        Set<String> out = new HashSet<>();
        if (!visiting.add(tag)) return out;
        for (String v : raw.getOrDefault(tag, List.of())) {
            if (v.startsWith("#")) out.addAll(resolveTag(v.substring(1), raw, done, visiting));
            else out.add(v.contains(":") ? v : "minecraft:" + v);
        }
        done.put(tag, out);
        return out;
    }

    /** Dev: why a chunk did or didn't produce a given structure. */
    public String debug(String id, ChunkPos c) {
        StringBuilder sb = new StringBuilder(id + "@" + c + ":");
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            for (var e : set.value().structures()) {
                if (!id(e.structure()).equals(id)) continue;
                StructurePlacement p = set.value().placement();
                sb.append(" placementChunk=").append(p.isStructureChunk(state, c.x(), c.z()));
                if (p instanceof RandomSpreadStructurePlacement rs) sb.append(" potential=").append(rs.getPotentialStructureChunk(seed, c.x(), c.z()));
                Structure s = e.structure().value();
                if (s instanceof JigsawStructure j) {
                    var ctx = new Structure.GenerationContext(null, generator, source, randomState, null, seed, c, height, s.biomes()::contains);
                    int startY = j.startHeight.sample(ctx.random(), new WorldGenerationContext(generator, height));
                    for (int dx = 0; dx <= 16; dx += 8) for (int dz = 0; dz <= 16; dz += 8) {
                        int x = c.getMinBlockX() + dx, z = c.getMinBlockZ() + dz;
                        int y = j.projectStartToHeightmap.isEmpty() ? startY : startY + generator.getFirstFreeHeight(x, z, j.projectStartToHeightmap.get(), height, randomState);
                        Holder<Biome> b = biomeAt(new BlockPos(x, y, z));
                        sb.append(" [").append(dx).append(',').append(dz).append(" y").append(y).append(' ')
                            .append(b.unwrapKey().map(k -> k.identifier().getPath()).orElse("?")).append(s.biomes().contains(b) ? " ok" : " NO").append(']');
                    }
                }
                sb.append(" resolved=").append(resolve(set.value(), c));
            }
        }
        return sb.toString();
    }

    /**
     * End cities within {@code radius} chunks that got a ship, with the exact elytra frame position. The city is
     * built with vanilla's own EndCityPieces (same random as the server), using the end_city templates from the jar.
     */
    public List<Ship> endShips(int cx, int cz, int radius) {
        if (dim != Level.END) return List.of();
        var templates = endCityTemplates();
        if (templates == null) return List.of();
        Holder<Structure> endCity = lookup.lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.END_CITY);
        List<Ship> out = new ArrayList<>();
        for (Hit city : scan(cx, cz, radius, c -> c.equals("end_city"))) {
            try {
                var start = endCity.value().generate(endCity, Level.END, null, generator, source, randomState, templates, seed,
                    city.chunk(), 0, height, endCity.value().biomes()::contains);
                if (!start.isValid()) continue;
                Ship ship = shipOf(city.pos(), start.getPieces());
                if (ship != null) out.add(ship);
            } catch (Throwable t) {
                Prism.LOG.debug("[structures] end city at {} failed", city.chunk(), t);
            }
        }
        return out;
    }

    /** The ship piece of an end city's pieces (also used by the dev test on the server's real pieces). */
    public static Ship shipOf(BlockPos city, List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces) {
        for (var piece : pieces) {
            if (!(piece instanceof net.minecraft.world.level.levelgen.structure.structures.EndCityPieces.EndCityPiece p) || !"ship".equals(p.templateName)) continue;
            BlockPos elytra = null;
            for (var info : p.template.filterBlocks(p.templatePosition, p.placeSettings, net.minecraft.world.level.block.Blocks.STRUCTURE_BLOCK)) {
                if (info.nbt() != null && info.nbt().getStringOr("metadata", "").startsWith("Elytra")) elytra = info.pos();
            }
            return new Ship(city, p.getBoundingBox().getCenter(), elytra);
        }
        return null;
    }

    /**
     * A template manager holding only vanilla's end_city templates from the game jar. Its constructor needs a world
     * save, so it's allocated bare and its repository pre-filled; vanilla's getOrCreate then finds everything there.
     */
    private static synchronized net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager endCityTemplates() {
        if (endTemplates != null) return endTemplates;
        try {
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var manager = (net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager)
                unsafe.allocateInstance(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager.class);
            Map<net.minecraft.resources.Identifier, Optional<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>> repo =
                new java.util.concurrent.ConcurrentHashMap<>();
            var fixer = net.minecraft.util.datafix.DataFixers.getDataFixer();
            try (var pack = net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource()) {
                pack.listResources(net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft", "structure/end_city", (id, supplier) -> {
                    String path = id.getPath(); // structure/end_city/ship.nbt
                    if (!path.endsWith(".nbt")) return;
                    var templateId = net.minecraft.resources.Identifier.withDefaultNamespace(path.substring("structure/".length(), path.length() - 4));
                    try (var in = supplier.get()) {
                        var tag = net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                        int version = net.minecraft.nbt.NbtUtils.getDataVersion(tag, 500);
                        var template = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
                        template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                            net.minecraft.util.datafix.DataFixTypes.STRUCTURE.updateToCurrentVersion(fixer, tag, version));
                        repo.put(templateId, Optional.of(template));
                    } catch (Exception e) {
                        Prism.LOG.warn("[structures] bad template {}", id, e);
                    }
                });
            }
            manager.structureRepository = repo;
            manager.sources = List.of();
            Prism.LOG.info("[structures] loaded {} end city templates", repo.size());
            return endTemplates = manager;
        } catch (Throwable t) {
            Prism.LOG.error("[structures] could not load end city templates", t);
            return null;
        }
    }

    private Holder<Biome> biomeAt(BlockPos p) {
        return source.getNoiseBiome(QuartPos.fromBlock(p.getX()), QuartPos.fromBlock(p.getY()), QuartPos.fromBlock(p.getZ()), randomState.sampler());
    }

    private static String id(Holder<Structure> structure) {
        return structure.unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown");
    }

    /** Groups vanilla structure ids into the names the module lists (village_plains -> village...). */
    public static String category(String id) {
        if (id.startsWith("village_")) return "village";
        if (id.startsWith("ruined_portal")) return "ruined_portal";
        if (id.startsWith("shipwreck")) return "shipwreck";
        if (id.startsWith("ocean_ruin")) return "ocean_ruin";
        if (id.startsWith("mineshaft")) return "mineshaft";
        return switch (id) {
            case "jungle_pyramid" -> "jungle_temple";
            case "pillager_outpost" -> "outpost";
            case "bastion_remnant" -> "bastion";
            default -> id;
        };
    }
}

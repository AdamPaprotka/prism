package dev.prismglass.xray;

import net.minecraft.world.level.biome.Biomes;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.HolderLookup.RegistryLookup;
import net.minecraft.world.level.biome.FeatureSorter.StepFeatureData;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration.TargetBlockState;

import dev.prismglass.Prism;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Stream;

/**
 * Predicts ore positions from the world seed by re-running vanilla ore generation client-side.
 *
 * <p>Faithful to {@code ChunkGenerator#generateFeatures}: the overworld/nether biome source and the global
 * feature index are rebuilt from the built-in registries exactly like the server, every chunk gets
 * {@code setPopulationSeed(seed, x, z)}, every ore feature {@code setDecoratorSeed(pop, index, step)}, positions
 * come from vanilla's own placement modifiers (same lazy stream order), and the vein/scatter algorithms are
 * line-by-line ports of {@link OreFeature} / {@link ScatteredOreFeature} that record instead of writing blocks.
 *
 * <p>Whether a candidate block becomes ore depends on what was there at generation time (stone/deepslate
 * test, and for diamonds/debris whether it touched air). That is reconstructed from the client's view of the
 * world, with every ore-looking block (anti-xray fakes included) mapped back to its host stone.
 *
 * <p>Works for vanilla worldgen. Servers with custom world generation or Paper's randomised per-feature
 * seeds ({@code feature-seeds}) generate ores differently and can't be predicted.
 */
public final class OreSimulator {
    public record Ore(BlockPos pos, Block block) {}

    private record OreRef(int step, int index, PlacedFeature placed, OreConfiguration config, boolean scattered) {}

    private static HolderLookup.Provider lookup;

    private final ClientLevel world;
    private final long seed;
    private final NoiseBasedChunkGenerator generator;
    private final HolderLookup.RegistryLookup<Biome> biomes;
    private final List<OreRef> ores = new ArrayList<>();
    private final WorldGenLevel fakeWorld;

    private OreSimulator(ClientLevel world, long seed, ResourceKey<Level> dim) {
        this.world = world;
        this.seed = seed;
        if (lookup == null) lookup = VanillaRegistries.createLookup();
        boolean nether = dim == Level.NETHER;
        var params = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(nether ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(params);
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(nether ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD);
        this.generator = new NoiseBasedChunkGenerator(source, settings);
        this.biomes = lookup.lookupOrThrow(Registries.BIOME);

        // identical to ChunkGenerator's indexedFeaturesListSupplier
        List<FeatureSorter.StepFeatureData> indexed = FeatureSorter.buildFeaturesPerStep(
            List.copyOf(source.possibleBiomes()), b -> b.value().getGenerationSettings().features(), true);
        for (int step = 0; step < indexed.size(); step++) {
            List<PlacedFeature> list = indexed.get(step).features();
            for (int i = 0; i < list.size(); i++) {
                PlacedFeature pf = list.get(i);
                ConfiguredFeature<?, ?> cf = pf.feature().value();
                if (cf.config() instanceof OreConfiguration cfg && (cf.feature() instanceof OreFeature || cf.feature() instanceof ScatteredOreFeature)) {
                    ores.add(new OreRef(step, i, pf, cfg, cf.feature() instanceof ScatteredOreFeature));
                }
            }
        }
        this.fakeWorld = makeFakeWorld();
        Prism.LOG.info("[xray] ore simulator ready: {} ore features for {}", ores.size(), dim.identifier());
    }

    public static OreSimulator create(ClientLevel world, long seed) {
        ResourceKey<Level> dim = world.dimension();
        if (dim != Level.OVERWORLD && dim != Level.NETHER) return null;
        try {
            return new OreSimulator(world, seed, dim);
        } catch (Throwable t) {
            Prism.LOG.error("[xray] could not build the ore simulator", t);
            return null;
        }
    }

    public boolean isFor(ClientLevel w) { return w == world; }

    // ---- generation-time world view ------------------------------------------------------------------

    /**
     * What the block probably was when ores generated: anything ore-like (incl. anti-xray fakes) was its host
     * stone; air/water stay (caves/aquifers existed before ores).
     */
    private BlockState genState(BlockPos pos, Map<BlockPos, BlockState> placed) {
        BlockState overlay = placed.get(pos);
        if (overlay != null) return overlay;
        BlockState s = world.getBlockState(pos);
        if (s.isAir() || !s.getFluidState().isEmpty()) return s;
        if (isOreLike(s)) {
            if (world.dimension() == Level.NETHER) return Blocks.NETHERRACK.defaultBlockState();
            return pos.getY() < 0 || s.getBlock().getDescriptionId().contains("deepslate")
                ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        return s;
    }

    private static boolean isOreLike(BlockState s) {
        return s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.DIAMOND_ORES)
            || s.is(BlockTags.EMERALD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.COPPER_ORES)
            || s.is(Blocks.ANCIENT_DEBRIS) || s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.NETHER_GOLD_ORE);
    }

    // ---- simulation -----------------------------------------------------------------------------------

    public List<Ore> simulate(ChunkPos cp, Set<Block> targets) {
        List<Ore> out = new ArrayList<>();
        Set<Holder<Biome>> area = biomesAround(cp);
        BlockPos origin = new BlockPos(cp.getMinBlockX(), world.getMinY(), cp.getMinBlockZ());
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long population = random.setDecorationSeed(seed, origin.getX(), origin.getZ());
        Map<BlockPos, BlockState> placed = new HashMap<>();

        for (OreRef ref : ores) {
            boolean wanted = false;
            for (OreConfiguration.TargetBlockState t : ref.config().targetStates) if (targets.contains(t.state.getBlock())) { wanted = true; break; }
            if (!wanted || !allowedIn(ref.placed(), area)) continue;

            random.setFeatureSeed(population, ref.index(), ref.step());
            PlacementContext ctx = new PlacementContext(fakeWorld, generator, Optional.of(ref.placed()));
            try {
                Stream<BlockPos> stream = Stream.of(origin);
                for (PlacementModifier m : ref.placed().placement()) {
                    stream = stream.flatMap(p -> m.getPositions(ctx, random, p));
                }
                stream.forEach(p -> {
                    if (ref.scattered()) scattered(ref.config(), random, p, placed, out, targets);
                    else vein(ref.config(), random, p, placed, out, targets);
                });
            } catch (Throwable t) {
                // a modifier needed more of the world than we provide: skip this feature
            }
        }
        return out;
    }

    private Set<Holder<Biome>> biomesAround(ChunkPos cp) {
        Set<ResourceKey<Biome>> keys = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            LevelChunk c = world.getChunkSource().getChunkNow(cp.x() + dx, cp.z() + dz);
            if (c == null) continue;
            for (LevelChunkSection s : c.getSections()) s.getBiomes().getAll(b -> b.unwrapKey().ifPresent(keys::add));
        }
        Set<Holder<Biome>> out = new HashSet<>();
        for (ResourceKey<Biome> k : keys) biomes.get(k).ifPresent(out::add);
        return out;
    }

    private static boolean allowedIn(PlacedFeature pf, Set<Holder<Biome>> area) {
        for (Holder<Biome> b : area) if (b.value().getGenerationSettings().hasFeature(pf)) return true;
        return false;
    }

    /** Port of OreFeature#generate + generateVeinPart. */
    private void vein(OreConfiguration config, RandomSource random, BlockPos blockPos, Map<BlockPos, BlockState> placed,
                      List<Ore> out, Set<Block> targets) {
        float f = random.nextFloat() * (float) Math.PI;
        float g = config.size / 8.0F;
        int i = Mth.ceil((config.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double startX = blockPos.getX() + Math.sin(f) * g;
        double endX = blockPos.getX() - Math.sin(f) * g;
        double startZ = blockPos.getZ() + Math.cos(f) * g;
        double endZ = blockPos.getZ() - Math.cos(f) * g;
        double startY = blockPos.getY() + random.nextInt(3) - 2;
        double endY = blockPos.getY() + random.nextInt(3) - 2;
        int x = blockPos.getX() - Mth.ceil(g) - i;
        int y = blockPos.getY() - 2 - i;
        int z = blockPos.getZ() - Mth.ceil(g) - i;
        int horizontalSize = 2 * (Mth.ceil(g) + i);
        int verticalSize = 2 * (2 + i);

        boolean ok = false;
        for (int s = x; s <= x + horizontalSize && !ok; s++) {
            for (int t = z; t <= z + horizontalSize; t++) {
                if (y <= world.getHeight(Heightmap.Types.WORLD_SURFACE, s, t)) { ok = true; break; }
            }
        }
        if (!ok) return;

        int j = config.size;
        BitSet bitSet = new BitSet(horizontalSize * verticalSize * horizontalSize);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        double[] ds = new double[j * 4];
        for (int k = 0; k < j; k++) {
            float fk = (float) k / j;
            double d = Mth.lerp((double) fk, startX, endX);
            double e = Mth.lerp((double) fk, startY, endY);
            double gg = Mth.lerp((double) fk, startZ, endZ);
            double h = random.nextDouble() * j / 16.0;
            double l = ((Mth.sin((float) Math.PI * fk) + 1.0F) * h + 1.0) / 2.0;
            ds[k * 4] = d;
            ds[k * 4 + 1] = e;
            ds[k * 4 + 2] = gg;
            ds[k * 4 + 3] = l;
        }
        for (int k = 0; k < j - 1; k++) {
            if (ds[k * 4 + 3] <= 0.0) continue;
            for (int m = k + 1; m < j; m++) {
                if (ds[m * 4 + 3] <= 0.0) continue;
                double d = ds[k * 4] - ds[m * 4];
                double e = ds[k * 4 + 1] - ds[m * 4 + 1];
                double gg = ds[k * 4 + 2] - ds[m * 4 + 2];
                double h = ds[k * 4 + 3] - ds[m * 4 + 3];
                if (h * h > d * d + e * e + gg * gg) {
                    if (h > 0.0) ds[m * 4 + 3] = -1.0; else ds[k * 4 + 3] = -1.0;
                }
            }
        }
        for (int mx = 0; mx < j; mx++) {
            double d = ds[mx * 4 + 3];
            if (d < 0.0) continue;
            double e = ds[mx * 4], gg = ds[mx * 4 + 1], h = ds[mx * 4 + 2];
            int n = Math.max(Mth.floor(e - d), x);
            int o = Math.max(Mth.floor(gg - d), y);
            int p = Math.max(Mth.floor(h - d), z);
            int q = Math.max(Mth.floor(e + d), n);
            int r = Math.max(Mth.floor(gg + d), o);
            int s = Math.max(Mth.floor(h + d), p);
            for (int t = n; t <= q; t++) {
                double u = (t + 0.5 - e) / d;
                if (u * u >= 1.0) continue;
                for (int v = o; v <= r; v++) {
                    double w = (v + 0.5 - gg) / d;
                    if (u * u + w * w >= 1.0) continue;
                    for (int aa = p; aa <= s; aa++) {
                        double ab = (aa + 0.5 - h) / d;
                        if (u * u + w * w + ab * ab >= 1.0 || world.isOutsideBuildHeight(v)) continue;
                        int ac = t - x + (v - y) * horizontalSize + (aa - z) * horizontalSize * verticalSize;
                        if (bitSet.get(ac)) continue;
                        bitSet.set(ac);
                        mutable.set(t, v, aa);
                        BlockState state = genState(mutable, placed);
                        for (OreConfiguration.TargetBlockState target : config.targetStates) {
                            if (OreFeature.canPlaceOre(state, pos -> genState(pos, placed), random, config, target, mutable)) {
                                BlockPos at = mutable.immutable();
                                placed.put(at, target.state);
                                if (targets.contains(target.state.getBlock())) out.add(new Ore(at, target.state.getBlock()));
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Port of ScatteredOreFeature#generate (ancient debris, nether gold...). */
    private void scattered(OreConfiguration config, RandomSource random, BlockPos origin, Map<BlockPos, BlockState> placed,
                           List<Ore> out, Set<Block> targets) {
        int count = random.nextInt(config.size + 1);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int j = 0; j < count; j++) {
            int spread = Math.min(j, 7);
            int dx = Math.round((random.nextFloat() - random.nextFloat()) * spread);
            int dy = Math.round((random.nextFloat() - random.nextFloat()) * spread);
            int dz = Math.round((random.nextFloat() - random.nextFloat()) * spread);
            mutable.setWithOffset(origin, dx, dy, dz);
            BlockState state = genState(mutable, placed);
            for (OreConfiguration.TargetBlockState target : config.targetStates) {
                if (OreFeature.canPlaceOre(state, pos -> genState(pos, placed), random, config, target, mutable)) {
                    BlockPos at = mutable.immutable();
                    placed.put(at, target.state);
                    if (targets.contains(target.state.getBlock())) out.add(new Ore(at, target.state.getBlock()));
                    break;
                }
            }
        }
    }

    // ---- minimal world for placement modifiers ---------------------------------------------------------

    /** Answers what vanilla's ore placement modifiers ask: height limits and (built-in) biomes. */
    private WorldGenLevel makeFakeWorld() {
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
            new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getMinY" -> world.getMinY();
                case "getHeight" -> world.getHeight();
                case "getMaxY" -> world.getMaxY();
                case "getSeed" -> seed;
                case "getBiome" -> {
                    Holder<Biome> client = world.getBiome((BlockPos) args[0]);
                    yield client.unwrapKey().flatMap(biomes::get).orElseGet(() -> biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
                }
                case "getBlockState" -> genState((BlockPos) args[0], Map.of());
                case "isOutsideBuildHeight" -> args[0] instanceof Integer yy ? world.isOutsideBuildHeight(yy) : world.isOutsideBuildHeight((BlockPos) args[0]);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "PrismOreSimWorld";
                default -> {
                    // derived helpers (getMinSectionY, getSectionsCount...) are interface defaults built on the above
                    if (method.isDefault()) yield java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    throw new UnsupportedOperationException(method.getName());
                }
            });
    }
}

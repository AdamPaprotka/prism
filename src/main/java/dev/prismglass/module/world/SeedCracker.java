package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.render.Xray;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.xray.SeedMath;
import dev.prismglass.xray.StructureMatcher;
import dev.prismglass.xray.StructurePredictor;
import dev.prismglass.xray.StructurePredictor.BiomeSample;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Cracks the world seed from what the client sees, like SeedcrackerX:
 * <ol>
 *   <li>Exact structure starts (StructureFinder's jungle temples, desert pyramids, swamp huts, village centres) and
 *       the End pillar heights pin down the low 48 bits, the "structure seed" ({@link SeedMath}).</li>
 *   <li>The top 16 bits: the hashed seed the server sends on join, if it isn't hidden; otherwise the two shapes real
 *       seeds have (a random {@code nextLong}, or a typed text seed's hash) and finally all 65536, each checked against
 *       biomes you've seen.</li>
 * </ol>
 * Everything is kept per server in .minecraft/prism/seedcracker, so data adds up over sessions. Doesn't work where
 * the server changed its structure seeds (spigot.yml) or on terrain that isn't vanilla 26.x generation.
 */
public class SeedCracker extends Module {
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int MAX_BIOMES = 300;

    public final BoolSetting autoApply = bool("AutoApply", true, "Put a verified seed into .seed, so StructureFinder and Xray seed mode use it.");
    public final NumberSetting threads = num("Threads", Math.max(1, CORES - 3), 1, CORES, 1, "CPU threads for the search (low priority, in the background).");
    public final BoolSetting hashedSeed = bool("HashedSeed", true, "Finish with the hashed seed the server sends on join (instant and exact, unless the server hides it).");
    public final BoolSetting biomes = bool("Biomes", true, "Check candidate seeds against biomes you've seen (for servers that hide the hashed seed).");
    public final BoolSetting notify = bool("Notify", true, "Chat messages for new structures and the result.");

    private record Start(String id, int chunkX, int chunkZ) {}

    // per-server data
    private String server = "";
    private final Map<Start, SeedMath.Obs> starts = new LinkedHashMap<>();
    private final int[] pillars = new int[10];
    private final Map<ResourceKey<Level>, List<BiomeSample>> biomeSamples = new HashMap<>();
    private final Set<Long> sampledChunks = new HashSet<>();
    private Long result;
    private boolean resultVerified;
    private boolean dirty, changed;
    private int saveIn;

    // scanning
    private static final long TICK_BUDGET_NS = 1_000_000;
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Set<Long> scanned = new HashSet<>();
    private final Map<Long, Integer> retryAt = new HashMap<>();
    private int ticks;

    // the search
    private volatile Thread job;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile SeedMath.Search search;
    private volatile String status = "collecting";
    private final AtomicInteger sweepDone = new AtomicInteger();
    private volatile int sweepTotal;

    public SeedCracker() {
        super("SeedCracker", "Cracks the world seed from structures, End pillars and biomes you've seen.", Category.WORLD);
        Arrays.fill(pillars, -1);
    }

    @Override
    public void onEnable() {
        switchServer();
        queue.clear();
        scanned.clear();
        retryAt.clear();
        changed = true;
    }

    @Override
    public void onDisable() {
        stopJob();
        save();
    }

    @Override
    public void onWorldJoin() {
        if (isEnabled()) onEnable();
    }

    @Override
    public String getInfo() {
        if (result != null) return resultVerified ? "cracked" : "likely";
        SeedMath.Search s = search;
        if (job != null && s != null) return Math.round(s.progress() * 100) + "%";
        if (job != null && sweepTotal > 0) return Math.round(100.0 * sweepDone.get() / sweepTotal) + "%";
        return starts.size() + " found";
    }

    // ---- collecting ----------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        ticks++;
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        int r = Math.max(2, mc.options.getEffectiveRenderDistance());
        for (var it = retryAt.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() > ticks) continue;
            it.remove();
            scanned.remove(e.getKey());
        }
        if (queue.isEmpty()) {
            for (int d = 0; d <= r; d++) {
                for (int x = cx - d; x <= cx + d; x++) for (int z = cz - d; z <= cz + d; z++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != d) continue;
                    long key = ChunkPos.pack(x, z);
                    if (!scanned.contains(key)) queue.add(key);
                }
            }
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            long key = queue.poll();
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            scanned.add(key);
            if (result == null) collect(chunk, key);
        }
        scanned.removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - cx), Math.abs(ChunkPos.getZ(k) - cz)) > r + 8);
        if (result == null && mc.level.dimension() == Level.END && ticks % 20 == 0) scanPillars();
        if (changed && job == null && result == null) {
            changed = false;
            startJob();
        }
        if (dirty && --saveIn <= 0) save();
    }

    private void collect(LevelChunk chunk, long key) {
        StructureMatcher.Result found = StructureMatcher.scanCached(mc.level, chunk);
        if (found.retry()) retryAt.put(key, ticks + 40);
        for (StructureMatcher.Find f : found.finds()) {
            if (!f.exact() || mc.level.dimension() != Level.OVERWORLD) continue;
            Start s = new Start(f.id(), f.start().x(), f.start().z());
            if (starts.containsKey(s)) continue;
            SeedMath.Obs obs = StructurePredictor.observation(f.id(), f.start());
            if (obs == null || !obs.possible()) continue;
            starts.put(s, obs);
            markChanged();
            if (notify.get()) ChatUtil.info("SeedCracker: " + f.category().replace('_', ' ') + " at " + f.pos().getX() + ", " + f.pos().getZ()
                + " (" + starts.size() + " structures, ~" + Math.round(bits()) + " of 48 bits)");
        }
        // biomes: what the chunk packet says at its centre, two heights
        ResourceKey<Level> dim = mc.level.dimension();
        if (biomes.get() && (dim == Level.OVERWORLD || dim == Level.NETHER) && sampledChunks.add(dim == Level.NETHER ? ~key : key)) {
            List<BiomeSample> list = biomeSamples.computeIfAbsent(dim, k -> new ArrayList<>());
            if (list.size() < MAX_BIOMES) {
                int qx = (chunk.getPos().x() << 2) + 2, qz = (chunk.getPos().z() << 2) + 2;
                for (int y : dim == Level.NETHER ? new int[]{40} : new int[]{64, 0}) {
                    int qy = y >> 2;
                    chunk.getNoiseBiome(qx, qy, qz).unwrapKey().ifPresent(k -> list.add(new BiomeSample(qx, qy, qz, k.identifier().toString())));
                }
                if (list.size() == 12 || list.size() % 50 == 0) markChanged();
            }
        }
    }

    /** End pillars: bedrock on top of each tower, at heights 76 + 3 * size; the order of sizes comes from the seed. */
    private void scanPillars() {
        for (int i = 0; i < 10; i++) {
            if (pillars[i] >= 0) continue;
            int x = Mth.floor(42.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 10) * i)));
            int z = Mth.floor(42.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 10) * i)));
            if (mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            for (int size = 0; size < 10; size++) {
                int h = 76 + size * 3;
                if (mc.level.getBlockState(new BlockPos(x, h, z)).is(Blocks.BEDROCK) && mc.level.getBlockState(new BlockPos(x, h - 1, z)).is(Blocks.OBSIDIAN)) {
                    pillars[i] = h;
                    markChanged();
                    if (notify.get()) ChatUtil.info("SeedCracker: End pillar " + (i + 1) + " is " + h + " high (" + knownPillars() + "/10)");
                    break;
                }
            }
        }
    }

    private int knownPillars() {
        int n = 0;
        for (int p : pillars) if (p >= 0) n++;
        return n;
    }

    /** Pillar keys (sorted) that give these pillar heights (-1 = not seen), the way vanilla shuffles the sizes. */
    private static int[] pillarKeys(int[] pillars) {
        List<Integer> keys = new ArrayList<>();
        for (int key = 0; key < 1 << 16; key++) {
            var sizes = Util.toShuffledList(java.util.stream.IntStream.range(0, 10), RandomSource.createThreadLocalInstance(key));
            boolean ok = true;
            for (int i = 0; i < 10 && ok; i++) if (pillars[i] >= 0 && pillars[i] != 76 + sizes.getInt(i) * 3) ok = false;
            if (ok) keys.add(key);
        }
        return keys.stream().mapToInt(Integer::intValue).toArray();
    }

    private double bits() {
        double b = 0;
        for (SeedMath.Obs o : starts.values()) b += o.bits();
        return b;
    }

    private void markChanged() {
        changed = true;
        dirty = true;
        saveIn = 100;
    }

    // ---- cracking ----------------------------------------------------------------------------------------------

    private void stopJob() {
        cancel.set(true);
        Thread t = job;
        if (t != null) t.interrupt();
        job = null;
        search = null;
    }

    private void startJob() {
        List<SeedMath.Obs> obs = new ArrayList<>(starts.values());
        int[] pillarState = pillars.clone();
        Map<ResourceKey<Level>, List<BiomeSample>> samples = new HashMap<>();
        biomeSamples.forEach((k, v) -> samples.put(k, List.copyOf(v)));
        Long hashed = hashedSeed.get() ? mc.level.getBiomeManager().biomeZoomSeed : null;
        int nThreads = threads.getInt();
        String forServer = server;
        cancel.set(false);
        Thread t = new Thread(() -> {
            try {
                crack(obs, pillarState, samples, hashed, nThreads, forServer);
            } catch (InterruptedException ignored) {
            } catch (Throwable e) {
                Prism.LOG.error("[seedcracker] failed", e);
                status = "error: " + e;
            } finally {
                search = null;
                sweepTotal = 0;
                job = null;
            }
        }, "Prism SeedCracker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        job = t;
        t.start();
    }

    private void crack(List<SeedMath.Obs> obs, int[] pillarState, Map<ResourceKey<Level>, List<BiomeSample>> samples, Long hashed, int nThreads,
                       String forServer) throws InterruptedException {
        double have = 0;
        for (SeedMath.Obs o : obs) have += o.bits();
        int[] keys = null;
        int known = 0;
        for (int p : pillarState) if (p >= 0) known++;
        if (known >= 3) {
            keys = pillarKeys(pillarState);
            if (keys.length == 0) keys = null; // torn-down or modded pillars: ignore them
            else have += 16 - Math.log(keys.length) / Math.log(2);
        }
        boolean hasHash = hashed != null && hashed != 0;
        // with the hashed seed every candidate can be checked exactly, so a few thousand are fine
        if (have < (hasHash ? 34 : 40)) { status = "collecting: " + obs.size() + " structures, ~" + Math.round(have) + " of 48 bits"; return; }
        status = "searching structure seed";
        SeedMath.Search s = new SeedMath.Search(obs, keys, nThreads, cancel::get);
        search = s;
        List<Long> low = s.run();
        search = null;
        if (cancel.get()) return;
        if (s.refused != null) { status = "collecting: need more structures (~" + Math.round(have) + " of 48 bits)"; return; }
        if (low.isEmpty() && obs.size() >= 4) {
            // one bad start (a player-built copy, a structure from older terrain) makes nothing fit: try without each
            for (SeedMath.Obs skip : obs) {
                List<SeedMath.Obs> rest = new ArrayList<>(obs);
                rest.remove(skip);
                status = "nothing fits, trying without " + skip;
                SeedMath.Search again = new SeedMath.Search(rest, keys, nThreads, cancel::get);
                search = again;
                List<Long> r = again.run();
                search = null;
                if (cancel.get()) return;
                if (!r.isEmpty()) {
                    low = r;
                    obs = rest;
                    mc.execute(() -> {
                        starts.values().remove(skip);
                        markChanged();
                        changed = false;
                        if (notify.get()) ChatUtil.info("SeedCracker: ignoring " + skip + ", it doesn't fit with the others");
                    });
                    break;
                }
            }
        }
        if (low.isEmpty()) {
            status = "no structure seed fits: the server likely changed its structure seeds, or this terrain isn't 26.x vanilla";
            if (notify.get()) mc.execute(() -> ChatUtil.error("SeedCracker: " + status));
            return;
        }
        if (low.size() > (hasHash ? 8192 : 256)) { status = low.size() + " structure seeds fit, keep exploring"; return; }
        status = low.size() + " structure seed" + (low.size() == 1 ? "" : "s") + ", finding the full seed";

        // the top 16 bits
        Long found = null;
        boolean verified = false;
        if (hasHash) {
            status = "matching the hashed seed";
            found = byHash(low, hashed, nThreads);
            verified = found != null;
        }
        List<BiomeSample> bs = new ArrayList<>(samples.getOrDefault(Level.OVERWORLD, List.of()));
        ResourceKey<Level> bdim = Level.OVERWORLD;
        if (bs.size() < 12 && samples.getOrDefault(Level.NETHER, List.of()).size() > bs.size()) { bs = samples.get(Level.NETHER); bdim = Level.NETHER; }
        StructurePredictor.BiomeCheck check = bs.size() >= 12 ? StructurePredictor.BiomeCheck.of(bdim) : null;
        int allowed = Math.max(1, bs.size() / 10); // old pre-1.18 chunks keep their old biomes
        List<Long> shaped = new ArrayList<>();
        for (long l : low) {
            shaped.addAll(SeedMath.randomSeeds(l));
            Long text = SeedMath.textSeed(l);
            if (text != null) shaped.add(text);
        }
        if (found == null && check != null) {
            List<Long> ok = new ArrayList<>();
            for (long seed : shaped) if (check.mismatches(seed, bs, allowed) <= allowed) ok.add(seed);
            if (ok.size() == 1) { found = ok.get(0); verified = true; }
        }
        if (found == null && check != null && low.size() <= 16) {
            status = "checking biomes for all 65536 top bits";
            found = sweep(low, check, bs, allowed, nThreads);
            verified = found != null;
        }
        if (found == null && shaped.size() == 1 && low.size() == 1) found = shaped.get(0); // unverified best guess
        if (cancel.get() || !forServer.equals(server)) return;
        if (found == null) {
            status = low.size() == 1 ? "structure seed " + low.get(0) + " (full seed needs the hashed seed or more biomes)"
                : low.size() + " structure seeds fit, keep exploring";
            return;
        }
        // vanilla's own placement code agrees with every start
        for (SeedMath.Obs o : obs) {
            if (!StructurePredictor.vanillaFits(found, o.id, new ChunkPos(o.chunkX, o.chunkZ))) {
                status = "vanilla disagrees with the result for " + o + ", ignoring it";
                return;
            }
        }
        long seed = found;
        boolean sure = verified;
        mc.execute(() -> finish(seed, sure));
    }

    /** All 65536 top bits of each structure seed against the server's hashed seed (SHA-256), on the worker threads. */
    private Long byHash(List<Long> low, long hashed, int nThreads) throws InterruptedException {
        sweepTotal = low.size() << 16;
        sweepDone.set(0);
        Set<Long> ok = ConcurrentHashMap.newKeySet();
        AtomicInteger next = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < nThreads; w++) {
            Thread t = new Thread(() -> {
                int i;
                while (!cancel.get() && ok.isEmpty() && (i = next.getAndAdd(4096)) < sweepTotal) {
                    for (int j = i; j < Math.min(i + 4096, sweepTotal); j++) {
                        long seed = ((long) (j & 0xFFFF) << 48) | low.get(j >>> 16);
                        if (BiomeManager.obfuscateSeed(seed) == hashed) ok.add(seed);
                    }
                    sweepDone.addAndGet(4096);
                }
            }, "Prism SeedCracker hash");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) t.join();
        sweepTotal = 0;
        return ok.size() == 1 ? ok.iterator().next() : null;
    }

    /** All 65536 top bits of each structure seed against the biomes, on the worker threads. */
    private Long sweep(List<Long> low, StructurePredictor.BiomeCheck check, List<BiomeSample> bs, int allowed, int nThreads) throws InterruptedException {
        sweepTotal = low.size() << 16;
        sweepDone.set(0);
        Set<Long> ok = ConcurrentHashMap.newKeySet();
        List<Thread> workers = new ArrayList<>();
        AtomicInteger next = new AtomicInteger();
        for (int w = 0; w < nThreads; w++) {
            Thread t = new Thread(() -> {
                int i;
                while (!cancel.get() && (i = next.getAndIncrement()) < sweepTotal) {
                    long seed = ((long) (i & 0xFFFF) << 48) | low.get(i >>> 16);
                    if (check.mismatches(seed, bs, allowed) <= allowed) ok.add(seed);
                    sweepDone.incrementAndGet();
                }
            }, "Prism SeedCracker biomes");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) t.join();
        return ok.size() == 1 ? ok.iterator().next() : null;
    }

    private void finish(long seed, boolean verified) {
        result = seed;
        resultVerified = verified;
        dirty = true;
        save();
        status = (verified ? "cracked: " : "likely (unverified): ") + seed;
        ChatUtil.good("SeedCracker: seed " + (verified ? "" : "is probably ") + seed + (verified ? "" : " (couldn't verify it: no hashed seed or biomes)"));
        if (verified && autoApply.get()) {
            Prism.modules().get(Xray.class).seed.set(String.valueOf(seed));
            ChatUtil.info("SeedCracker: put into .seed (StructureFinder seed mode works now)");
        }
    }

    // ---- status / command ------------------------------------------------------------------------------------------

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("Server: " + (server.isEmpty() ? "?" : server));
        Map<String, Integer> byId = new TreeMap<>();
        for (Start s : starts.keySet()) byId.merge(s.id(), 1, Integer::sum);
        out.add("Structures: " + starts.size() + (byId.isEmpty() ? "" : " " + byId) + ", ~" + Math.round(bits()) + " of 48 bits");
        out.add("End pillars: " + knownPillars() + "/10, biome samples: " + biomeSamples.getOrDefault(Level.OVERWORLD, List.of()).size()
            + " overworld, " + biomeSamples.getOrDefault(Level.NETHER, List.of()).size() + " nether");
        SeedMath.Search s = search;
        out.add("Status: " + (s != null ? s.stage() + " " + Math.round(s.progress() * 100) + "%" : status));
        if (result != null) out.add("Seed: " + result + (resultVerified ? "" : " (unverified)"));
        return out;
    }

    public void reset() {
        stopJob();
        starts.clear();
        Arrays.fill(pillars, -1);
        biomeSamples.clear();
        sampledChunks.clear();
        scanned.clear();
        result = null;
        resultVerified = false;
        status = "collecting";
        markChanged();
        save();
    }

    public void retry() {
        stopJob();
        result = null;
        changed = true;
    }

    // ---- per-server storage ---------------------------------------------------------------------------------------

    private static String serverKey() {
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        if (mc.getSingleplayerServer() != null) return "sp-" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return "unknown";
    }

    private static Path file(String key) {
        return FabricLoader.getInstance().getGameDir().resolve("prism").resolve("seedcracker").resolve(key.replaceAll("[^a-zA-Z0-9._-]", "_") + ".txt");
    }

    private void switchServer() {
        String key = serverKey();
        if (key.equals(server)) return;
        stopJob();
        if (!server.isEmpty()) save();
        server = key;
        starts.clear();
        Arrays.fill(pillars, -1);
        biomeSamples.clear();
        sampledChunks.clear();
        result = null;
        resultVerified = false;
        status = "collecting";
        load();
    }

    private void load() {
        Path f = file(server);
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                switch (p[0]) {
                    case "start" -> {
                        ChunkPos c = new ChunkPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                        SeedMath.Obs o = StructurePredictor.observation(p[1], c);
                        if (o != null) starts.put(new Start(p[1], c.x(), c.z()), o);
                    }
                    case "pillar" -> pillars[Integer.parseInt(p[1])] = Integer.parseInt(p[2]);
                    case "biome" -> {
                        ResourceKey<Level> dim = p[1].equals("nether") ? Level.NETHER : Level.OVERWORLD;
                        BiomeSample b = new BiomeSample(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), p[5]);
                        biomeSamples.computeIfAbsent(dim, k -> new ArrayList<>()).add(b);
                        long ck = ChunkPos.pack(b.qx() >> 2, b.qz() >> 2);
                        sampledChunks.add(dim == Level.NETHER ? ~ck : ck);
                    }
                    case "seed" -> { result = Long.parseLong(p[1]); resultVerified = p.length < 3 || !p[2].equals("unverified"); }
                    default -> {}
                }
            }
            if (result != null) status = (resultVerified ? "cracked: " : "likely (unverified): ") + result;
        } catch (Exception e) {
            Prism.LOG.warn("[seedcracker] could not read {}", f, e);
        }
    }

    private void save() {
        if (!dirty || server.isEmpty()) return;
        dirty = false;
        List<String> lines = new ArrayList<>();
        for (Start s : starts.keySet()) lines.add("start " + s.id() + " " + s.chunkX() + " " + s.chunkZ());
        for (int i = 0; i < 10; i++) if (pillars[i] >= 0) lines.add("pillar " + i + " " + pillars[i]);
        biomeSamples.forEach((dim, list) -> {
            String d = dim == Level.NETHER ? "nether" : "overworld";
            for (BiomeSample b : list) lines.add("biome " + d + " " + b.qx() + " " + b.qy() + " " + b.qz() + " " + b.biome());
        });
        if (result != null) lines.add("seed " + result + (resultVerified ? "" : " unverified"));
        try {
            Path f = file(server);
            Files.createDirectories(f.getParent());
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Prism.LOG.warn("[seedcracker] could not save", e);
        }
    }
}

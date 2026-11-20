package dev.prismglass.xray;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The seedcracker's number crunching. Structure placement ({@code RandomSpreadStructurePlacement}) and the End pillars
 * both seed a {@code LegacyRandomSource}, which keeps only the low 48 bits, so those 48 bits ("structure seed") are
 * cracked first from where structures start; the top 16 bits come afterwards (hashed seed, random-seed shape, biomes).
 *
 * <p>The 48-bit search lifts bits from the bottom up: for {@code nextInt(n)} with {@code n = m * 2^k} (m odd), the low k
 * bits of the result are bits 17..17+k of the LCG state, which only depend on the low 17+k bits of the seed. So the low
 * 17+k bits are enumerated first (2^20 for temples) and filtered by every structure, the End pillar key (16 bits that
 * only depend on the low 32 bits) lifts to 32, and only the survivors get the full search over the remaining bits.
 */
public final class SeedMath {
    public static final long MULT = 0x5DEECE66DL, ADD = 0xBL, MASK = (1L << 48) - 1;
    /** MULT^-1 mod 2^48 (steps the LCG backwards). */
    public static final long MULT_INV = inverse48(MULT);
    /** Work (seed checks) the search refuses to go beyond: more structures are needed then. */
    public static final long WORK_LIMIT = 1L << 38;

    private SeedMath() {}

    /** One structure start: its set's spread placement and the start chunk. */
    public static final class Obs {
        public final String id;
        public final int spacing, separation, salt, chunkX, chunkZ;
        public final boolean triangular;
        final int limit, offX, offZ, k;
        final boolean pow2;
        final long key;
        // odd part of the limit and its inverse mod 2^32, for the fast divisibility test
        final int odd, oddInv, oddLim;

        public Obs(String id, int spacing, int separation, int salt, boolean triangular, int chunkX, int chunkZ) {
            this.id = id;
            this.spacing = spacing;
            this.separation = separation;
            this.salt = salt;
            this.triangular = triangular;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            limit = spacing - separation;
            int rx = Math.floorDiv(chunkX, spacing), rz = Math.floorDiv(chunkZ, spacing);
            offX = chunkX - rx * spacing;
            offZ = chunkZ - rz * spacing;
            key = rx * 341873128712L + rz * 132897987541L + salt;
            pow2 = (limit & -limit) == limit;
            k = triangular || pow2 ? 0 : Integer.numberOfTrailingZeros(limit);
            odd = limit >>> Integer.numberOfTrailingZeros(limit);
            int inv = odd;
            for (int i = 0; i < 5; i++) inv *= 2 - odd * inv;
            oddInv = inv;
            oddLim = (int) (0xFFFFFFFFL / odd);
        }

        /** Its offsets can't be reached at all (separation too close to the region edge): bad data. */
        public boolean possible() { return offX >= 0 && offZ >= 0 && offX < limit && offZ < limit; }

        /** About how many bits of the seed this pins down. */
        public double bits() { return 2 * Math.log(limit) / Math.log(2) - (triangular ? 0.6 : 0); }

        @Override public boolean equals(Object o) { return o instanceof Obs b && b.salt == salt && b.chunkX == chunkX && b.chunkZ == chunkZ; }
        @Override public int hashCode() { return Objects.hash(salt, chunkX, chunkZ); }
        @Override public String toString() { return id + "@" + chunkX + "," + chunkZ; }
    }

    // ---- exact vanilla checks ------------------------------------------------------------------------------------

    private static int next31(long[] s) {
        s[0] = (s[0] * MULT + ADD) & MASK;
        return (int) (s[0] >>> 17);
    }

    /** {@code BitRandomSource.nextInt(n)}, java.util.Random's algorithm. */
    private static int nextInt(long[] s, int n) {
        if ((n & -n) == n) return (int) ((n * (long) next31(s)) >> 31);
        int bits, val;
        do {
            bits = next31(s);
            val = bits % n;
        } while (bits - val + (n - 1) < 0);
        return val;
    }

    private static int evaluate(long[] s, Obs o) {
        return o.triangular ? (nextInt(s, o.limit) + nextInt(s, o.limit)) / 2 : nextInt(s, o.limit);
    }

    /** Exactly vanilla's {@code getPotentialStructureChunk(seed, region) == start chunk}. */
    public static boolean fits(long seed, Obs o) {
        long[] s = {((seed + o.key) ^ MULT) & MASK};
        return evaluate(s, o) == o.offX && evaluate(s, o) == o.offZ;
    }

    /** The End pillar key: {@code RandomSource.create(seed).nextLong() & 0xFFFF} (only the low 32 bits of the seed matter). */
    public static int pillarKey(long seed) {
        long s0 = (seed ^ MULT) & 0xFFFFFFFFL;
        long s1 = (s0 * MULT + ADD) & 0xFFFFFFFFL;
        long s2 = (s1 * MULT + ADD) & 0xFFFFFFFFL;
        return (int) (s2 >>> 16) & 0xFFFF;
    }

    private static boolean all(long seed, List<Obs> obs, int[] pillarKeys) {
        for (Obs o : obs) if (!fits(seed, o)) return false;
        return pillarKeys == null || Arrays.binarySearch(pillarKeys, pillarKey(seed)) >= 0;
    }

    /** Low k bits of both offsets, using only the low {@code bits} bits of the seed ({@code bits >= 17 + k}). */
    private static boolean liftOk(long low, int bits, Obs o) {
        long m = bits >= 64 ? -1L : (1L << bits) - 1;
        long s1 = ((((low + o.key) ^ MULT) & m) * MULT + ADD) & m;
        int km = (1 << o.k) - 1;
        if (((int) (s1 >>> 17) & km) != (o.offX & km)) return false;
        long s2 = (s1 * MULT + ADD) & m;
        return ((int) (s2 >>> 17) & km) == (o.offZ & km);
    }

    // ---- the search --------------------------------------------------------------------------------------------

    /** A 48-bit search; {@link #run()} does the work, {@link #progress()} can be read from any thread. */
    public static final class Search {
        private final List<Obs> obs;
        private final int[] pillarKeys;
        private final int threads;
        private final BooleanSupplier cancelled;
        private final AtomicLong done = new AtomicLong();
        private volatile long total = 1;
        private volatile String stage = "starting";
        /** Set when the search would take too long for the data it has. */
        public volatile String refused;

        /** pillarKeys: possible End pillar keys (sorted), or null when the pillars weren't seen. */
        public Search(List<Obs> obs, int[] pillarKeys, int threads, BooleanSupplier cancelled) {
            this.obs = new ArrayList<>(obs);
            // most informative first: failing seeds drop out sooner
            this.obs.sort(Comparator.comparingDouble(Obs::bits).reversed());
            this.pillarKeys = pillarKeys;
            this.threads = Math.max(1, threads);
            this.cancelled = cancelled;
        }

        public double progress() { return Math.min(1.0, done.get() / (double) total); }
        public String stage() { return stage; }

        /** Every 48-bit structure seed that fits all the data (normally exactly one). Empty if refused or none fit. */
        public List<Long> run() throws InterruptedException {
            List<Obs> lift = new ArrayList<>();
            int maxK = 0;
            for (Obs o : obs) if (o.k > 0) { lift.add(o); maxK = Math.max(maxK, o.k); }
            int level = lift.isEmpty() ? 0 : 17 + maxK;
            long[] low = {0};
            if (level > 0) {
                stage = "low bits";
                LongList kept = new LongList();
                for (long s = 0; s < 1L << level; s++) {
                    boolean ok = true;
                    for (Obs o : lift) if (!liftOk(s, level, o)) { ok = false; break; }
                    if (ok) kept.add(s);
                }
                low = kept.toArray();
            }
            if (pillarKeys != null && level < 32) {
                long work = (long) low.length << (32 - level);
                if (work > WORK_LIMIT) { refused = "too little data"; return List.of(); }
                stage = "pillars";
                low = extendToPillars(low, level);
                level = 32;
            }
            if (cancelled.getAsBoolean()) return List.of();
            long work = (long) low.length << (48 - level);
            if (low.length == 0) return List.of();
            if (work > WORK_LIMIT || work < 0) { refused = "too little data"; return List.of(); }
            stage = "searching";
            return finish(low, level);
        }

        private long[] extendToPillars(long[] low, int level) throws InterruptedException {
            int span = 32 - level;
            long chunk = 1L << Math.min(span, 22);
            total = (long) low.length << span;
            done.set(0);
            ConcurrentLinkedQueue<Long> out = new ConcurrentLinkedQueue<>();
            List<Callable<Void>> tasks = new ArrayList<>();
            for (long base : low) {
                for (long h0 = 0; h0 < 1L << span; h0 += chunk) {
                    long from = h0, to = Math.min(1L << span, h0 + chunk);
                    tasks.add(() -> {
                        for (long h = from; h < to; h++) {
                            long s = base | (h << level);
                            if (Arrays.binarySearch(pillarKeys, pillarKey(s)) >= 0) out.add(s);
                        }
                        done.addAndGet(to - from);
                        return null;
                    });
                }
            }
            runAll(tasks);
            long[] r = new long[out.size()];
            int i = 0;
            for (long v : out) r[i++] = v;
            Arrays.sort(r);
            return r;
        }

        private List<Long> finish(long[] low, int level) throws InterruptedException {
            int span = 48 - level;
            long chunk = 1L << Math.min(span, 24);
            total = (long) low.length << span;
            done.set(0);
            Obs first = null;
            for (Obs o : obs) if (!o.triangular) { first = o; break; }
            Obs fast = first;
            Set<Long> out = ConcurrentHashMap.newKeySet();
            List<Callable<Void>> tasks = new ArrayList<>();
            for (long base : low) {
                for (long g0 = 0; g0 < 1L << span; g0 += chunk) {
                    long from = g0, to = Math.min(1L << span, g0 + chunk);
                    tasks.add(() -> {
                        if (fast != null) scanFast(base, level, from, to, fast, out);
                        else for (long h = from; h < to && !cancelled.getAsBoolean(); h++) {
                            long s = base | (h << level);
                            if (all(s, obs, pillarKeys)) out.add(s);
                        }
                        done.addAndGet(to - from);
                        return null;
                    });
                }
            }
            runAll(tasks);
            List<Long> r = new ArrayList<>(out);
            Collections.sort(r);
            return r;
        }

        /**
         * Steps the first structure's LCG state directly instead of the seed: with the low {@code level} bits fixed,
         * the high bits of {@code (seed + key) ^ MULT} run through every value exactly once, and the state after one
         * step grows by a constant each time. One add, a multiply and two compares per candidate.
         */
        private void scanFast(long base, int level, long from, long to, Obs o, Set<Long> out) {
            long maskH = (1L << (48 - level)) - 1, maskL = (1L << level) - 1;
            long t = (base + o.key) & MASK;
            long carry = t >>> level;
            long low0 = (t & maskL) ^ (MULT & maskL);
            long xorH = MULT >>> level;
            long step = (MULT << level) & MASK;
            long s1 = ((((from << level) | low0) & MASK) * MULT + ADD) & MASK;
            int n = o.limit;
            for (long g = from; g < to; g++, s1 = (s1 + step) & MASK) {
                if ((g & 0xFFFFF) == 0 && cancelled.getAsBoolean()) return;
                int u = (int) (s1 >>> 17);
                if (o.pow2) {
                    if ((int) ((n * (long) u) >> 31) != o.offX) continue;
                } else {
                    int d = u - o.offX;
                    if (d < 0 || Integer.compareUnsigned(d * o.oddInv, o.oddLim) > 0) continue;
                }
                long s2 = (s1 * MULT + ADD) & MASK;
                int v = (int) (s2 >>> 17);
                if ((o.pow2 ? (int) ((n * (long) v) >> 31) : v % n) != o.offZ) continue;
                long h = ((g ^ xorH) - carry) & maskH;
                long seed = base | (h << level);
                if (all(seed, obs, pillarKeys)) out.add(seed);
            }
        }

        private void runAll(List<Callable<Void>> tasks) throws InterruptedException {
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread th = new Thread(r, "Prism SeedCracker");
                th.setDaemon(true);
                th.setPriority(Thread.MIN_PRIORITY);
                return th;
            });
            try {
                List<Future<Void>> fs = new ArrayList<>();
                for (Callable<Void> c : tasks) fs.add(pool.submit(() -> cancelled.getAsBoolean() ? null : c.call()));
                for (Future<Void> f : fs) {
                    try { f.get(); } catch (ExecutionException e) { throw new RuntimeException(e.getCause()); }
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---- the top 16 bits ----------------------------------------------------------------------------------------

    /**
     * Full seeds with these low 48 bits that {@code RandomSource.create().nextLong()} can produce, i.e. what a world
     * made without typing a seed has. Usually exactly one.
     */
    public static List<Long> randomSeeds(long low48) {
        List<Long> out = new ArrayList<>();
        int b = (int) low48;
        for (long lo = 0; lo < 1 << 16; lo++) {
            long s2 = ((b & 0xFFFFFFFFL) << 16) | lo;
            long s1 = ((s2 - ADD) * MULT_INV) & MASK;
            int a = (int) (s1 >>> 16);
            long seed = ((long) a << 32) + b;
            if ((seed & MASK) == low48 && !out.contains(seed)) out.add(seed);
        }
        return out;
    }

    /** The full seed if this could be a typed text seed ({@code String.hashCode}, a sign-extended int), else null. */
    public static Long textSeed(long low48) {
        long v = (int) low48;
        return (v & MASK) == low48 ? v : null;
    }

    private static long inverse48(long a) {
        long x = a;
        for (int i = 0; i < 6; i++) x *= 2 - a * x;
        return x & MASK;
    }

    /** Growable long array (the low-bit stage keeps up to a million survivors). */
    private static final class LongList {
        long[] a = new long[1024];
        int n;
        void add(long v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        long[] toArray() { return Arrays.copyOf(a, n); }
    }
}

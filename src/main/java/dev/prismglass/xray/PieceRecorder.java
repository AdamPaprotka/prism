package dev.prismglass.xray;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

/**
 * Runs a vanilla structure piece's {@code postProcess} into a fake level that only remembers what was placed, so the
 * exact block layout of code-built structures (jungle temple, desert pyramid, swamp hut) comes from vanilla itself
 * instead of a hand copy. Everything below {@link #GROUND_Y} reads as stone, so "fill down to the ground" loops stop
 * right away; those foundation columns aren't part of the layout (their length depends on the real terrain). Blocks
 * the piece places lower down on purpose (the pyramid's TNT room) are still recorded.
 */
public final class PieceRecorder {
    /** Recorded pieces are built at y 64; below this the fake level reads as solid ground. */
    public static final int GROUND_Y = 60;

    private static final ThreadLocal<Boolean> RECORDING = ThreadLocal.withInitial(() -> false);

    private PieceRecorder() {}

    /** True while a recording runs on this thread (StructureRecordMixin skips chunk post-processing marks). */
    public static boolean recording() { return RECORDING.get(); }

    /** Every block the piece places, by world position (the piece's own coordinates, as constructed). */
    public static Map<BlockPos, BlockState> record(StructurePiece piece) {
        Map<BlockPos, BlockState> placed = new HashMap<>();
        RandomSource random = RandomSource.create(0L);
        WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setBlock" -> {
                    if (args[0] instanceof BlockPos p && args[1] instanceof BlockState s) placed.put(p.immutable(), s);
                    yield true;
                }
                case "getBlockState" -> {
                    BlockPos p = (BlockPos) args[0];
                    BlockState s = placed.get(p);
                    yield s != null ? s : p.getY() < GROUND_Y ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                }
                case "getFluidState" -> {
                    BlockState s = placed.get((BlockPos) args[0]);
                    yield (s == null ? Blocks.AIR.defaultBlockState() : s).getFluidState();
                }
                case "getRandom" -> random;
                case "getSeed" -> 0L;
                case "getMinY" -> -64;
                case "getMaxY" -> 319;
                case "getHeight" -> args == null || args.length == 0 ? 384 : 64;
                case "isClientSide" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "PrismPieceRecorder";
                default -> defaultValue(method.getReturnType());
            });
        BoundingBox everything = new BoundingBox(-30_000_000, -64, -30_000_000, 30_000_000, 319, 30_000_000);
        RECORDING.set(true);
        try {
            piece.postProcess(level, null, null, random, everything, new ChunkPos(0, 0), BlockPos.ZERO);
        } finally {
            RECORDING.set(false);
        }
        return placed;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == void.class) return null;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}

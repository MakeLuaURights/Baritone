package dev.baritonestudio.task;

import dev.baritonestudio.path.Terrain;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/** Быстрый поиск ближайшего подходящего блока в загруженных чанках. */
public final class BlockScanner {
    private BlockScanner() {}

    public static BlockPos nearest(ClientWorld w, BlockPos origin, int radius, BlockMatcher m, Set<BlockPos> exclude, Terrain t) {
        return nearest(w, origin, radius, m, exclude, t, null, false, false);
    }

    /** Есть ли у блока хотя бы одна открытая (не сплошная) грань. */
    private static boolean exposed(ClientWorld w, int x, int y, int z) {
        BlockPos.Mutable q = new BlockPos.Mutable();
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
            if (!w.getBlockState(q.set(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ())).isOpaqueFullCube()) return true;
        }
        return false;
    }

    /**
     * @param region  необязательный фильтр области (null — везде)
     * @param topDown сначала самые верхние слои (для очистки области сверху вниз)
     * @param legit   только блоки с открытой гранью (legitMine)
     */
    public static BlockPos nearest(ClientWorld w, BlockPos origin, int radius, BlockMatcher m, Set<BlockPos> exclude, Terrain t,
                                   java.util.function.Predicate<BlockPos> region, boolean topDown, boolean legit) {
        java.util.List<BlockPos> l = nearestN(w, origin, radius, m, exclude, t, region, topDown, legit, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Ближайшие n подходящих блоков по возрастанию «расстояния» (для выбора самой дешёвой цели по длине пути). */
    public static java.util.List<BlockPos> nearestN(ClientWorld w, BlockPos origin, int radius, BlockMatcher m, Set<BlockPos> exclude, Terrain t,
                                   java.util.function.Predicate<BlockPos> region, boolean topDown, boolean legit, int n) {
        java.util.List<BlockPos> bestList = new java.util.ArrayList<>();
        java.util.List<Double> bestDs = new java.util.ArrayList<>();
        int cx0 = (origin.getX() - radius) >> 4, cx1 = (origin.getX() + radius) >> 4;
        int cz0 = (origin.getZ() - radius) >> 4, cz1 = (origin.getZ() + radius) >> 4;
        double r2 = (double) radius * radius;
        BlockPos.Mutable mp = new BlockPos.Mutable();
        int bottomSection = w.getBottomSectionCoord();

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                WorldChunk chunk = w.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                ChunkSection[] secs = chunk.getSectionArray();
                for (int i = 0; i < secs.length; i++) {
                    ChunkSection sec = secs[i];
                    if (sec == null || sec.isEmpty()) continue;
                    int baseY = (bottomSection + i) << 4;
                    if (baseY > origin.getY() + radius || baseY + 15 < origin.getY() - radius) continue;
                    if (!sec.hasAny(m::test)) continue;
                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                BlockState st = sec.getBlockState(lx, ly, lz);
                                if (!m.test(st)) continue;
                                int x = (cx << 4) + lx, y = baseY + ly, z = (cz << 4) + lz;
                                double dx = x - origin.getX(), dy = (y - origin.getY()) * 1.5, dz = z - origin.getZ();
                                double d = dx * dx + dy * dy + dz * dz;
                                if (d > r2) continue;
                                if (topDown) d = (4096 - y) * 1_000_000.0 + dx * dx + dz * dz;
                                if (bestDs.size() >= n && d >= bestDs.get(bestDs.size() - 1)) continue;
                                mp.set(x, y, z);
                                if (region != null && !region.test(mp)) continue;
                                if (legit && !exposed(w, x, y, z)) continue;
                                if (exclude != null && exclude.contains(mp)) continue;
                                if (t != null && Terrain.nextToLava(t, x, y, z)) continue;
                                int at = 0;
                                while (at < bestDs.size() && bestDs.get(at) <= d) at++;
                                bestDs.add(at, d);
                                bestList.add(at, mp.toImmutable());
                                if (bestDs.size() > n) {
                                    bestDs.remove(bestDs.size() - 1);
                                    bestList.remove(bestList.size() - 1);
                                }
                            }
                        }
                    }
                }
            }
        }
        return bestList;
    }
}

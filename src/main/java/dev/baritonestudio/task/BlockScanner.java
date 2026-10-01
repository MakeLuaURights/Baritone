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
        int cx0 = (origin.getX() - radius) >> 4, cx1 = (origin.getX() + radius) >> 4;
        int cz0 = (origin.getZ() - radius) >> 4, cz1 = (origin.getZ() + radius) >> 4;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
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
                                if (d > r2 || d >= bestD) continue;
                                mp.set(x, y, z);
                                if (exclude != null && exclude.contains(mp)) continue;
                                if (t != null && Terrain.nextToLava(t, x, y, z)) continue;
                                bestD = d;
                                best = mp.toImmutable();
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}

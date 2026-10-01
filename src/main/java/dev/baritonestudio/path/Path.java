package dev.baritonestudio.path;

import java.util.List;
import net.minecraft.util.math.BlockPos;

/** Результат поиска: последовательность позиций ног и типы переходов. */
public record Path(List<BlockPos> nodes, List<Move> moves, boolean reachesGoal) {
    public int size() {
        return nodes.size();
    }

    public BlockPos end() {
        return nodes.get(nodes.size() - 1);
    }
}

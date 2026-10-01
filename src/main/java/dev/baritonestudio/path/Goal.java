package dev.baritonestudio.path;

import java.util.List;
import net.minecraft.util.math.BlockPos;

/** Цель поиска пути. */
public interface Goal {
    boolean isEnd(int x, int y, int z);

    /** Оценка оставшейся стоимости (допустимая эвристика). */
    double heuristic(int x, int y, int z);

    static double octile(double dx, double dz) {
        dx = Math.abs(dx);
        dz = Math.abs(dz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
    }

    /** Встать ровно на клетку. */
    record Block(int x, int y, int z) implements Goal {
        public Block(BlockPos p) {
            this(p.getX(), p.getY(), p.getZ());
        }

        @Override
        public boolean isEnd(int px, int py, int pz) {
            return px == x && py == y && pz == z;
        }

        @Override
        public double heuristic(int px, int py, int pz) {
            double dy = y - py;
            return octile(x - px, z - pz) + (dy > 0 ? dy * 1.6 : -dy * 0.6);
        }
    }

    /** Подойти на расстояние range (по горизонтали и по высоте). */
    record Near(int x, int y, int z, int range) implements Goal {
        @Override
        public boolean isEnd(int px, int py, int pz) {
            int dx = px - x, dy = py - y, dz = pz - z;
            return dx * dx + dy * dy + dz * dz <= range * range;
        }

        @Override
        public double heuristic(int px, int py, int pz) {
            double dy = Math.abs(y - py);
            return Math.max(0, octile(x - px, z - pz) + dy * 0.8 - range);
        }
    }

    /** Достичь высоты Y (спуск/подъём в шахте). */
    record Level(int y) implements Goal {
        @Override
        public boolean isEnd(int px, int py, int pz) {
            return py == y;
        }

        @Override
        public double heuristic(int px, int py, int pz) {
            double dy = y - py;
            return dy > 0 ? dy * 2.5 : -dy * 1.5;
        }
    }

    /**
     * Встать так, чтобы целевой блок был вплотную к ногам или голове (можно добывать, не двигаясь).
     * Допустимые позиции: любая из 4 боковых сторон на уровне ног/головы, блок над головой, блок под ногами.
     */
    record Adjacent(BlockPos target) implements Goal {
        @Override
        public boolean isEnd(int px, int py, int pz) {
            int dx = target.getX() - px, dy = target.getY() - py, dz = target.getZ() - pz;
            int man = Math.abs(dx) + Math.abs(dz);
            if (man == 1 && (dy == 0 || dy == 1)) return true; // сбоку на уровне ног или головы
            if (man == 0 && (dy == 2 || dy == -1)) return true; // над головой / под ногами
            return false;
        }

        @Override
        public double heuristic(int px, int py, int pz) {
            double dy = target.getY() - py;
            double h = octile(target.getX() - px, target.getZ() - pz) - 1;
            if (dy > 1) h += (dy - 1) * 1.6;
            else if (dy < 0) h += (-dy - 1) * 0.6;
            return Math.max(0, h);
        }
    }

    /** Любая из нескольких целей. */
    record Any(List<Goal> goals) implements Goal {
        @Override
        public boolean isEnd(int x, int y, int z) {
            for (Goal g : goals) if (g.isEnd(x, y, z)) return true;
            return false;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double best = Double.MAX_VALUE;
            for (Goal g : goals) best = Math.min(best, g.heuristic(x, y, z));
            return best == Double.MAX_VALUE ? 0 : best;
        }
    }
}

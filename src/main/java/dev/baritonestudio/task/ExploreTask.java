package dev.baritonestudio.task;

import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.util.L;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/** Исследование: идёт в сторону чанков, в которых ещё не был, пока его не остановят. */
public final class ExploreTask extends Task {
    private final Navigator nav = new Navigator();
    private final Set<Long> visited = new HashSet<>();
    private final Random rnd = new Random();
    private double heading = Double.NaN;
    private int goalX, goalZ;
    private boolean hasGoal;
    private int legs;
    private int idle;

    @Override
    public String name() {
        return L.t("task.explore");
    }

    @Override
    public String status() {
        return L.t("status.exploring", visited.size(), legs);
    }

    @Override
    public Navigator navigator() {
        return nav;
    }

    @Override
    public void start(Actor a) {
        nav.breakMode = Navigator.BreakMode.CONFIG;
    }

    @Override
    public void stop(Actor a) {
        nav.clear();
    }

    private void mark(BlockPos p, int r) {
        int cx = p.getX() >> 4, cz = p.getZ() >> 4;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) visited.add(ChunkPos.toLong(cx + dx, cz + dz));
    }

    private void chooseGoal(Actor a) {
        BlockPos me = a.player().getBlockPos();
        double bestScore = -1, bestAng = 0;
        for (int i = 0; i < 16; i++) {
            double ang = i * Math.PI / 8;
            int score = 0;
            for (int d = 24; d <= 160; d += 16) {
                int cx = (me.getX() + (int) (Math.cos(ang) * d)) >> 4, cz = (me.getZ() + (int) (Math.sin(ang) * d)) >> 4;
                if (!visited.contains(ChunkPos.toLong(cx, cz))) score++;
            }
            double s = score + rnd.nextDouble() * 0.8;
            if (!Double.isNaN(heading)) s += 1.5 * Math.cos(ang - heading); // не метаться туда-сюда
            if (s > bestScore) {
                bestScore = s;
                bestAng = ang;
            }
        }
        heading = bestAng;
        goalX = me.getX() + (int) (Math.cos(bestAng) * 64);
        goalZ = me.getZ() + (int) (Math.sin(bestAng) * 64);
        hasGoal = true;
        legs++;
        nav.setGoal(new Goal.XZ(goalX, goalZ, 6));
    }

    @Override
    public void tick(Actor a) {
        mark(a.player().getBlockPos(), 3);
        if (!hasGoal) chooseGoal(a);
        switch (nav.tick(a)) {
            case ARRIVED -> hasGoal = false;
            case FAILED -> {
                mark(new BlockPos(goalX, 0, goalZ), 4); // туда не пройти – считаем изученным
                hasGoal = false;
                if (++idle > 12) fail(L.t("status.explore_stuck"));
            }
            default -> idle = 0;
        }
    }
}

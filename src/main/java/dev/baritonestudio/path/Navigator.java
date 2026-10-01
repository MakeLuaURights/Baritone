package dev.baritonestudio.path;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.task.Actor;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

/** Ставит цель, считает путь, ведёт по нему и перестраивает его при сбоях. */
public final class Navigator {
    public enum Status { IDLE, MOVING, ARRIVED, FAILED }

    private Goal goal;
    private PathFollower follower;
    private int replans;
    private int fallWait;
    private BlockPos lastPartialEnd;
    public String error = "";
    public enum BreakMode { CONFIG, ALWAYS, NEVER }

    /** Можно ли ломать блоки на пути: по настройкам, всегда (добыча/туннель) или никогда (повтор записи). */
    public BreakMode breakMode = BreakMode.CONFIG;

    public void setGoal(Goal g) {
        goal = g;
        follower = null;
        replans = 0;
        fallWait = 0;
        lastPartialEnd = null;
        error = "";
    }

    public void clear() {
        goal = null;
        follower = null;
    }

    public Goal goal() {
        return goal;
    }

    public Path currentPath() {
        return follower != null ? follower.path() : null;
    }

    public Status tick(Actor a) {
        if (goal == null) return Status.IDLE;
        ClientPlayerEntity p = a.player();
        ModConfig cfg = ModConfig.get();
        BlockPos pb = p.getBlockPos();
        boolean grounded = p.isOnGround() || p.isTouchingWater() || p.isClimbing();

        if (goal.isEnd(pb.getX(), pb.getY(), pb.getZ()) && (grounded || fallWait > 40)) {
            return Status.ARRIVED;
        }
        if (!grounded && follower == null) {
            fallWait++;
            return Status.MOVING;
        }

        boolean canBreak = switch (breakMode) {
            case ALWAYS -> true;
            case NEVER -> false;
            default -> cfg.allowBreak;
        };
        boolean scaffold = cfg.allowScaffold && breakMode != BreakMode.NEVER;
        Terrain t = new Terrain(a.world(), canBreak, scaffold, cfg.maxFall);

        if (follower == null) {
            boolean pillar = scaffold && a.hasScaffold();
            Path path = Pathfinder.find(t, pb, goal, 30000, 70, pillar);
            if (path == null || path.size() < 2) {
                error = "путь не найден";
                return Status.FAILED;
            }
            if (!path.reachesGoal() && path.end().equals(lastPartialEnd)) {
                error = "нет прогресса на пути";
                return Status.FAILED;
            }
            lastPartialEnd = path.reachesGoal() ? null : path.end();
            follower = new PathFollower(path);
        }

        PathFollower.State s = follower.tick(a, t);
        switch (s) {
            case RUNNING:
                return Status.MOVING;
            case ARRIVED:
                boolean full = follower.path().reachesGoal();
                follower = null;
                if (full) return Status.ARRIVED;
                return Status.MOVING; // частичный путь – считаем продолжение на следующем тике
            default:
                if (++replans > 8) {
                    error = follower.lastError.isEmpty() ? "не удалось дойти" : follower.lastError;
                    return Status.FAILED;
                }
                follower = null;
                return Status.MOVING;
        }
    }
}

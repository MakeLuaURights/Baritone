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
    private double bestH = Double.MAX_VALUE;
    private int stagnation;
    private int smallHops;
    private int startDelay;
    private int histTick;
    private final java.util.ArrayDeque<BlockPos> history = new java.util.ArrayDeque<>();
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
        bestH = Double.MAX_VALUE;
        stagnation = 0;
        smallHops = 0;
        history.clear();
        histTick = 0;
        // человек сначала «понимает куда идти», а не срывается с места мгновенно
        startDelay = dev.baritonestudio.task.Human.get().on() ? dev.baritonestudio.task.Human.get().reaction() : 0;
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

        if (startDelay > 0) {
            startDelay--;
            return Status.MOVING;
        }

        // детектор «хождения по кругу»: слишком часто бывали в одном и том же месте, а цель так и не достигнута
        if (++histTick % 20 == 0) {
            history.addLast(pb);
            if (history.size() > 30) history.removeFirst();
            if (history.size() >= 20) {
                int near = 0;
                for (BlockPos h : history) if (h.getSquaredDistance(pb) <= 4) near++;
                if (near >= 14) {
                    error = "хожу по кругу";
                    return Status.FAILED;
                }
            }
        }

        if (follower == null) {
            boolean pillar = scaffold && a.hasScaffold();
            // прогресс: после нескольких перепостроений расстояние до цели обязано уменьшаться
            double h = goal.heuristic(pb.getX(), pb.getY(), pb.getZ());
            if (h < bestH - 0.6) {
                bestH = h;
                stagnation = 0;
            } else if (++stagnation > 5) {
                error = "нет прогресса";
                return Status.FAILED;
            }
            Path path = Pathfinder.find(t, pb, goal, 30000, 70, pillar);
            if (path == null || path.size() < 2) {
                error = "путь не найден";
                return Status.FAILED;
            }
            if (!path.reachesGoal() && path.end().equals(lastPartialEnd)) {
                error = "нет прогресса на пути";
                return Status.FAILED;
            }
            dev.baritonestudio.util.Storage.LOG.info("Navigator: путь {} -> {} ({} узлов, до цели: {}), h={}", pb.toShortString(), path.end().toShortString(), path.size(), path.reachesGoal(), String.format("%.1f", h));
            if (path.reachesGoal()) {
                smallHops = 0;
            } else {
                // частичный путь почти не приближает к цели несколько раз подряд – цель недостижима
                BlockPos e = path.end();
                double gain = goal.heuristic(pb.getX(), pb.getY(), pb.getZ()) - goal.heuristic(e.getX(), e.getY(), e.getZ());
                if (gain < 3.0) {
                    if (++smallHops >= 2) {
                        error = "цель недостижима";
                        return Status.FAILED;
                    }
                } else {
                    smallHops = 0;
                }
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

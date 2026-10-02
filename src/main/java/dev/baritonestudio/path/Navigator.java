package dev.baritonestudio.path;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.task.Actor;
import dev.baritonestudio.task.Human;
import dev.baritonestudio.util.Storage;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Ставит цель, ищет путь кусочками по тикам, ведёт по нему и заранее считает продолжение,
 * чтобы не останавливаться на стыках частичных путей (идея «splice» из Baritone).
 */
public final class Navigator {
    public enum Status { IDLE, MOVING, ARRIVED, FAILED }

    public enum BreakMode { CONFIG, ALWAYS, NEVER }

    /** Можно ли ломать блоки на пути: по настройкам, всегда (добыча/туннель) или никогда (повтор записи). */
    public BreakMode breakMode = BreakMode.CONFIG;
    public String error = "";

    private static final long SEARCH_SLICE_NS = 18_000_000L;  // пока персонаж стоит и ждёт путь, можно тратить почти треть тика
    private static final long PRECOMPUTE_SLICE_NS = 2_500_000L;
    private static final int PRECOMPUTE_WHEN_LEFT = 16;        // когда до конца частичного пути осталось столько узлов

    private Goal goal;
    private PathFollower follower;
    private PathSearch search;
    private PathSearch nextSearch;
    private Path next;
    private final Set<Long> favored = new HashSet<>();

    private int replans;
    private int fallWait;
    private double bestH = Double.MAX_VALUE;
    private int stagnation;
    private int smallHops;
    private int startDelay;
    private int histTick;
    private final java.util.ArrayDeque<BlockPos> history = new java.util.ArrayDeque<>();

    public void setGoal(Goal g) {
        goal = g;
        follower = null;
        search = null;
        nextSearch = null;
        next = null;
        favored.clear();
        replans = 0;
        fallWait = 0;
        bestH = Double.MAX_VALUE;
        stagnation = 0;
        smallHops = 0;
        history.clear();
        histTick = 0;
        // человек сначала «понимает куда идти», а не срывается с места мгновенно
        startDelay = Human.get().on() ? Human.get().reaction() : 0;
        error = "";
    }

    public void clear() {
        goal = null;
        follower = null;
        search = null;
        nextSearch = null;
        next = null;
    }

    public Goal goal() {
        return goal;
    }

    public Path currentPath() {
        return follower != null ? follower.path() : null;
    }

    private Terrain terrain(Actor a, boolean cached) {
        ModConfig cfg = ModConfig.get();
        boolean canBreak = switch (breakMode) {
            case ALWAYS -> true;
            case NEVER -> false;
            default -> cfg.allowBreak;
        };
        boolean scaffold = cfg.allowScaffold && breakMode != BreakMode.NEVER;
        Terrain t = new Terrain(a.world(), canBreak, scaffold, cfg.maxFall);
        t.canSprint = cfg.sprint && a.player().getHungerManager().canSprint();
        t.allowParkour = cfg.allowParkour;
        return cached ? t.cached() : t;
    }

    private boolean canPillar(Actor a) {
        ModConfig cfg = ModConfig.get();
        return cfg.allowScaffold && breakMode != BreakMode.NEVER && a.hasScaffold();
    }

    private void remember(Path path) {
        favored.clear();
        for (BlockPos p : path.nodes()) favored.add(p.asLong());
    }

    public Status tick(Actor a) {
        if (goal == null) return Status.IDLE;
        ClientPlayerEntity p = a.player();
        BlockPos pb = p.getBlockPos();
        boolean grounded = p.isOnGround() || p.isTouchingWater() || p.isClimbing();

        if (goal.isEnd(pb.getX(), pb.getY(), pb.getZ()) && (grounded || fallWait > 40)) {
            search = null;
            nextSearch = null;
            return Status.ARRIVED;
        }
        if (!grounded && follower == null && search == null) {
            fallWait++;
            return Status.MOVING;
        }
        if (startDelay > 0) {
            startDelay--;
            return Status.MOVING;
        }

        // детектор «хождения по кругу»: слишком часто бывали в одном и том же месте, а цель так и не достигнута
        if (search == null && ++histTick % 20 == 0) {
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

        // ---- 1) нет ни пути, ни поиска – запускаем поиск
        if (follower == null && search == null) {
            double h = goal.heuristic(pb.getX(), pb.getY(), pb.getZ());
            if (h < bestH - Costs.HEURISTIC * 0.6) {
                bestH = h;
                stagnation = 0;
            } else if (++stagnation > 5) {
                error = "нет прогресса";
                return Status.FAILED;
            }
            search = new PathSearch(terrain(a, true), pb, goal, canPillar(a), favored, 1200, 3500, 250_000);
        }

        // ---- 2) идёт поиск – считаем кусочками, персонаж пока стоит
        if (search != null) {
            if (!search.step(SEARCH_SLICE_NS)) return Status.MOVING;
            Path path = search.result();
            Storage.LOG.info("Navigator: {} -> {} ({} узлов, до цели: {}), раскрыто {} за {} мс",
                    search.origin().toShortString(), path == null ? "—" : path.end().toShortString(), path == null ? 0 : path.size(),
                    path != null && path.reachesGoal(), search.expanded, String.format("%.0f", search.activeMs()));
            if (path != null && Storage.LOG.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < path.size(); i++) sb.append(path.moves().get(i)).append('@').append(path.nodes().get(i).toShortString()).append(' ');
                Storage.LOG.debug("Navigator: ходы {}", sb);
            }
            search = null;
            if (path == null || path.size() < 2) {
                error = "путь не найден";
                return Status.FAILED;
            }
            if (path.reachesGoal()) {
                smallHops = 0;
            } else {
                // частичный путь почти не приближает к цели несколько раз подряд – цель недостижима
                BlockPos e = path.end();
                double gain = goal.heuristic(pb.getX(), pb.getY(), pb.getZ()) - goal.heuristic(e.getX(), e.getY(), e.getZ());
                if (gain < Costs.HEURISTIC * 3.0) {
                    if (++smallHops >= 2) {
                        error = "цель недостижима";
                        return Status.FAILED;
                    }
                } else {
                    smallHops = 0;
                }
            }
            remember(path);
            follower = new PathFollower(path);
        }

        // ---- 3) заранее считаем продолжение, пока идём по частичному пути
        Path cur = follower.path();
        if (next == null && nextSearch == null && !cur.reachesGoal() && cur.size() - 1 - follower.index() <= PRECOMPUTE_WHEN_LEFT) {
            nextSearch = new PathSearch(terrain(a, true), cur.end(), goal, canPillar(a), favored, 1000, 2500, 200_000);
        }
        if (nextSearch != null && nextSearch.step(PRECOMPUTE_SLICE_NS)) {
            Path r = nextSearch.result();
            if (r != null && r.size() >= 2) next = r;
            nextSearch = null;
        }

        // ---- 4) движение по пути
        PathFollower.State s = follower.tick(a, terrain(a, false));
        switch (s) {
            case RUNNING:
                return Status.MOVING;
            case ARRIVED: {
                boolean full = follower.path().reachesGoal();
                if (full) {
                    follower = null;
                    return Status.ARRIVED;
                }
                // стык: если продолжение уже посчитано и начинается рядом, переходим на него без паузы
                if (next != null && next.nodes().get(0).getSquaredDistance(pb) <= 4) {
                    remember(next);
                    follower = new PathFollower(next);
                    next = null;
                    smallHops = 0;
                    bestH = Double.MAX_VALUE;
                    return Status.MOVING;
                }
                follower = null;
                next = null;
                return Status.MOVING;
            }
            default:
                Storage.LOG.info("Navigator: сбой исполнения «{}» на узле {} из {} ({}), игрок {}", follower.lastError, follower.index(),
                        follower.path().size(), follower.index() + 1 < follower.path().size() ? follower.path().moves().get(follower.index() + 1) : "-", pb.toShortString());
                if (++replans > 8) {
                    error = follower.lastError.isEmpty() ? "не удалось дойти" : follower.lastError;
                    return Status.FAILED;
                }
                follower = null;
                next = null;
                nextSearch = null;
                return Status.MOVING;
        }
    }
}

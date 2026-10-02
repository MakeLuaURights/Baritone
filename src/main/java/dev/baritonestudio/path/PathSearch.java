package dev.baritonestudio.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.util.math.BlockPos;

/**
 * Возобновляемый A*: поиск можно вести кусочками по несколько миллисекунд за тик, не подвешивая игру.
 * Идеи заимствованы у Baritone: стоимость в тиках, несколько «лучших на данный момент» узлов с разными
 * коэффициентами для частичного пути, два таймаута (обычный и «если ничего не нашли»),
 * поощрение движения по уже пройденному маршруту (чтобы не метаться между равноценными путями).
 */
public final class PathSearch {
    /** Коэффициенты выбора лучшего частичного пути: оценка = h + g / коэффициент. */
    private static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
    /** Частичный путь короче этого расстояния бесполезен. */
    private static final double MIN_DIST_PATH = 5;
    private static final int[][] CARD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node {
        final int x, y, z;
        double g, f, h;
        Node parent;
        Move move = Move.START;
        int heapIndex = -1;
        boolean closed;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** Двоичная куча с обновлением приоритета. */
    private static final class Heap {
        private Node[] a = new Node[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void add(Node n) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            n.heapIndex = size;
            a[size++] = n;
            up(n.heapIndex);
        }

        void update(Node n) {
            up(n.heapIndex);
        }

        Node poll() {
            Node top = a[0];
            Node last = a[--size];
            a[size] = null;
            top.heapIndex = -1;
            if (size > 0) {
                a[0] = last;
                last.heapIndex = 0;
                down(0);
            }
            return top;
        }

        /** a важнее b: меньше f, а при равенстве – ближе к цели (иначе на ровной местности перебираются тысячи равноценных узлов). */
        private static boolean before(Node a, Node b) {
            double d = a.f - b.f;
            return d < -1e-6 || (d <= 1e-6 && a.h < b.h);
        }

        private void up(int i) {
            Node n = a[i];
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (!before(n, a[p])) break;
                a[i] = a[p];
                a[i].heapIndex = i;
                i = p;
            }
            a[i] = n;
            n.heapIndex = i;
        }

        private void down(int i) {
            Node n = a[i];
            int half = size >> 1;
            while (i < half) {
                int c = 2 * i + 1;
                if (c + 1 < size && before(a[c + 1], a[c])) c++;
                if (!before(a[c], n)) break;
                a[i] = a[c];
                a[i].heapIndex = i;
                i = c;
            }
            a[i] = n;
            n.heapIndex = i;
        }
    }

    private final Terrain t;
    private final Goal goal;
    private final boolean canPillar;
    private final Set<Long> favored;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final Heap open = new Heap();
    private final Node start;
    private final Node[] best = new Node[COEFFICIENTS.length];
    private final double[] bestValue = new double[COEFFICIENTS.length];

    private long activeNanos;
    private final long primaryNanos, failureNanos;
    private final int maxNodes;
    public int expanded;
    /** Сколько раз упёрлись в незагруженный чанк: как только их много, возвращаем лучший частичный путь (как pathingMaxChunkBorderFetch). */
    private int borderHits;
    private static final int MAX_BORDER_HITS = 200;
    private boolean done;
    private Path result;

    public PathSearch(Terrain t, BlockPos from, Goal goal, boolean canPillar, Set<Long> favored, long primaryMs, long failureMs, int maxNodes) {
        this.t = t.cached();
        this.goal = goal;
        this.canPillar = canPillar;
        this.favored = favored;
        this.primaryNanos = primaryMs * 1_000_000L;
        this.failureNanos = failureMs * 1_000_000L;
        this.maxNodes = maxNodes;
        start = new Node(from.getX(), from.getY(), from.getZ());
        start.h = goal.heuristic(start.x, start.y, start.z);
        start.f = start.h;
        nodes.put(from.asLong(), start);
        open.add(start);
        java.util.Arrays.fill(bestValue, Double.MAX_VALUE);
    }

    public BlockPos origin() {
        return new BlockPos(start.x, start.y, start.z);
    }

    public boolean isDone() {
        return done;
    }

    /** Результат: путь, либо null если пути нет. Доступен после завершения. */
    public Path result() {
        return result;
    }

    public double activeMs() {
        return activeNanos / 1e6;
    }

    /** Продолжить поиск не дольше budget наносекунд. @return true, когда поиск закончен */
    public boolean step(long budgetNanos) {
        if (done) return true;
        long t0 = System.nanoTime();
        long limit = t0 + budgetNanos;
        int iter = 0;
        while (!open.isEmpty()) {
            Node n = open.poll();
            n.closed = true;
            if (goal.isEnd(n.x, n.y, n.z)) {
                finish(build(n, true), t0);
                return true;
            }
            expand(n);
            expanded++;
            if (borderHits >= MAX_BORDER_HITS) {
                Path partial = bestPartial();
                if (partial != null) {
                    finish(partial, t0);
                    return true;
                }
            }
            if ((++iter & 31) == 0) {
                long now = System.nanoTime();
                long total = activeNanos + (now - t0);
                if (expanded >= maxNodes || total > primaryNanos) {
                    Path partial = bestPartial();
                    if (partial != null || total > failureNanos || expanded >= maxNodes * 2) {
                        finish(partial, t0);
                        return true;
                    }
                }
                if (now >= limit) {
                    activeNanos += now - t0;
                    return false;
                }
            }
        }
        finish(bestPartial(), t0);
        return true;
    }

    private void finish(Path p, long t0) {
        activeNanos += System.nanoTime() - t0;
        result = p;
        done = true;
    }

    private Path bestPartial() {
        for (int i = 0; i < best.length; i++) {
            Node b = best[i];
            if (b == null) continue;
            double dx = b.x - start.x, dy = b.y - start.y, dz = b.z - start.z;
            if (dx * dx + dy * dy + dz * dz > MIN_DIST_PATH * MIN_DIST_PATH && b.h < start.h - 1) return build(b, false);
        }
        return null;
    }

    private static Path build(Node end, boolean reaches) {
        List<BlockPos> pos = new ArrayList<>();
        List<Move> mv = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            pos.add(new BlockPos(n.x, n.y, n.z));
            mv.add(n.move);
        }
        Collections.reverse(pos);
        Collections.reverse(mv);
        return new Path(pos, mv, reaches);
    }

    private void relax(Node from, int x, int y, int z, double cost, Move move) {
        if (cost >= Costs.INF) return;
        long key = BlockPos.asLong(x, y, z);
        if (favored != null && favored.contains(key)) cost *= 0.5; // не метаться: вернее идти по уже выбранному маршруту
        Node n = nodes.get(key);
        double g = from.g + cost;
        if (n == null) {
            n = new Node(x, y, z);
            n.h = goal.heuristic(x, y, z);
            n.g = Double.MAX_VALUE;
            nodes.put(key, n);
        } else if (n.closed || g >= n.g - 0.01) {
            return;
        }
        boolean fresh = n.heapIndex < 0 && !n.closed;
        n.g = g;
        n.f = g + n.h;
        n.parent = from;
        n.move = move;
        if (fresh) open.add(n);
        else open.update(n);
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double v = n.h + g / COEFFICIENTS[i];
            if (v < bestValue[i]) {
                bestValue[i] = v;
                best[i] = n;
            }
        }
    }

    private boolean standable(int x, int y, int z) {
        return t.solidTop(x, y - 1, z) || t.climbable(x, y, z) || t.water(x, y, z);
    }

    private void expand(Node n) {
        int x = n.x, y = n.y, z = n.z;
        if (!t.inBounds(y)) return;
        double run = t.canSprint ? Costs.SPRINT : Costs.WALK;

        for (int[] d : CARD) {
            int nx = x + d[0], nz = z + d[1];
            if (!t.loaded(nx, nz)) {
                borderHits++;
                continue;
            }

            double cF = t.clearCost(nx, y, nz);
            double cH = t.clearCost(nx, y + 1, nz);

            if (cF < Costs.INF && cH < Costs.INF) {
                boolean mining = cF + cH > 0;
                if (standable(nx, y, nz)) {
                    double base = t.water(nx, y, nz) ? Costs.WALK_WATER : (mining ? Costs.WALK : run);
                    relax(n, nx, y, nz, base + cF + cH, Move.FLAT);
                } else {
                    // падение
                    for (int dd = 1; dd <= t.maxFall + 6; dd++) {
                        int ty = y - dd;
                        if (!t.inBounds(ty)) break;
                        if (!t.passable(nx, ty, nz)) break;
                        boolean water = t.water(nx, ty, nz);
                        boolean ladder = t.climbable(nx, ty, nz);
                        if (water || ladder || t.solidTop(nx, ty - 1, nz)) {
                            if (dd <= t.maxFall || water) {
                                relax(n, nx, ty, nz, Costs.WALK_OFF + Costs.FALL[dd] + Costs.CENTER_AFTER_FALL + cF + cH, Move.DESCEND);
                            }
                            break;
                        }
                    }
                }
            }

            // подъём на блок
            if (t.solidTop(nx, y, nz)) {
                double cUp = t.clearCost(x, y + 2, z);
                double c1 = t.clearCost(nx, y + 1, nz);
                double c2 = t.clearCost(nx, y + 2, nz);
                if (cUp < Costs.INF && c1 < Costs.INF && c2 < Costs.INF) {
                    relax(n, nx, y + 1, nz, Costs.WALK + Costs.JUMP_ONE_BLOCK + cUp + c1 + c2, Move.ASCEND);
                }
            }

            // прыжок через пропасть
            if (t.allowParkour && t.solidTop(x, y - 1, z) && !t.climbable(x, y, z)) parkour(n, d[0], d[1]);
        }

        // диагонали (только по свободным клеткам, без ломания)
        for (int[] d : DIAG) {
            int nx = x + d[0], nz = z + d[1];
            if (!t.loaded(nx, nz)) continue;
            if (t.clearCost(nx, y, nz) != 0 || t.clearCost(nx, y + 1, nz) != 0) continue;
            if (t.clearCost(x + d[0], y, z) != 0 || t.clearCost(x + d[0], y + 1, z) != 0) continue;
            if (t.clearCost(x, y, z + d[1]) != 0 || t.clearCost(x, y + 1, z + d[1]) != 0) continue;
            if (!t.solidTop(nx, y - 1, nz)) continue;
            relax(n, nx, y, nz, run * Costs.SQRT2, Move.DIAGONAL);
        }

        // копать вниз
        if (t.allowBreak && t.solidTop(x, y - 1, z)) {
            double cd = t.clearCost(x, y - 1, z);
            if (cd < Costs.INF && cd > 0 && (t.solidTop(x, y - 2, z) || t.water(x, y - 2, z))) {
                relax(n, x, y - 1, z, cd + Costs.FALL[1] + Costs.CENTER_AFTER_FALL, Move.DIG_DOWN);
            }
        }

        // столб вверх (ставим блок под себя)
        if (canPillar && t.solidTop(x, y - 1, z) && !t.water(x, y, z)) {
            double ch = t.clearCost(x, y + 2, z);
            if (ch < Costs.INF) {
                relax(n, x, y + 1, z, Costs.PLACE + Costs.JUMP_ONE_BLOCK + ch, Move.PILLAR);
            }
        }

        // лестницы и лоза
        if (t.climbable(x, y, z)) {
            if (t.climbable(x, y + 1, z) && t.passable(x, y + 2, z)) relax(n, x, y + 1, z, Costs.LADDER_UP, Move.CLIMB_UP);
            if (t.climbable(x, y - 1, z)) relax(n, x, y - 1, z, Costs.LADDER_DOWN, Move.CLIMB_DOWN);
        }
    }

    /** Прыжок на d блоков по прямой (2..4) – как MovementParkour в Baritone. */
    private void parkour(Node n, int dx, int dz) {
        int x = n.x, y = n.y, z = n.z;
        // над стартом должна быть свободная голова для прыжка
        if (!t.passable(x, y + 2, z)) return;
        boolean runUp = n.parent != null && n.parent.y == y && n.parent.x == x - dx && n.parent.z == z - dz
                && (n.move == Move.FLAT || n.move == Move.PARKOUR);
        for (int d = 2; d <= 4; d++) {
            if (d >= 3 && !runUp) break;
            if (d == 4 && !t.canSprint) break;
            int lx = x + dx * d, lz = z + dz * d;
            if (!t.loaded(lx, lz)) break;
            // промежуточные колонки: свободны на высоте ног, головы и прыжка
            boolean clear = true;
            for (int i = 1; i < d && clear; i++) {
                int ix = x + dx * i, iz = z + dz * i;
                clear = t.passable(ix, y, iz) && t.passable(ix, y + 1, iz) && t.passable(ix, y + 2, iz);
            }
            if (!clear) break;
            // приземление
            if (t.passable(lx, y, lz) && t.passable(lx, y + 1, lz) && t.passable(lx, y + 2, lz)
                    && !t.water(lx, y, lz) && t.solidTop(lx, y - 1, lz)) {
                // если на пути есть опора, это просто ходьба – её найдёт обычный ход; прыгаем только через пустоту
                boolean gap = false;
                for (int i = 1; i < d; i++) if (!standable(x + dx * i, y, z + dz * i)) gap = true;
                if (gap) {
                    double base = d >= 4 ? Costs.SPRINT : (t.canSprint ? Costs.SPRINT : Costs.WALK);
                    relax(n, lx, y, lz, base * d + Costs.JUMP_PENALTY, Move.PARKOUR);
                }
            }
        }
    }
}

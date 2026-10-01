package dev.baritonestudio.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.util.math.BlockPos;

/** A* по сетке блоков. Умеет ходить, прыгать, спрыгивать, копать вниз и строить столб вверх. */
public final class Pathfinder {
    private static final double HEURISTIC_WEIGHT = 1.15;
    private static final int[][] CARD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        double g, f, h;
        Node parent;
        Move move = Move.START;
        boolean closed;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private Pathfinder() {}

    /**
     * @param maxNodes  предел раскрытых узлов
     * @param timeoutMs предел времени
     * @return путь, либо null если сдвинуться некуда. Если цель недостижима в пределах лимитов,
     * возвращается частичный путь к самой близкой точке (reachesGoal=false).
     */
    public static Path find(Terrain t, BlockPos start, Goal goal, int maxNodes, long timeoutMs, boolean canPillar) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();

        Node s = new Node(start.getX(), start.getY(), start.getZ());
        s.h = goal.heuristic(s.x, s.y, s.z);
        s.f = s.h * HEURISTIC_WEIGHT;
        nodes.put(start.asLong(), s);
        open.add(s);

        Node best = s;
        int expanded = 0;

        while (!open.isEmpty()) {
            Node n = open.poll();
            if (n.closed) continue;
            n.closed = true;

            if (goal.isEnd(n.x, n.y, n.z)) return build(n, true);

            if (n.h < best.h - 1e-9 || (Math.abs(n.h - best.h) < 1e-9 && n.g < best.g)) best = n;

            if (++expanded >= maxNodes) break;
            if ((expanded & 127) == 0 && System.nanoTime() > deadline) break;

            expand(t, goal, nodes, open, n, canPillar);
        }

        if (best == s) return null;
        // частичный путь имеет смысл, только если он реально приблизил нас к цели
        if (s.h - best.h < 0.5) return null;
        return build(best, false);
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

    private static void relax(Goal goal, Map<Long, Node> nodes, PriorityQueue<Node> open, Node from, int x, int y, int z, double cost, Move move) {
        long key = BlockPos.asLong(x, y, z);
        Node n = nodes.get(key);
        double g = from.g + cost;
        if (n == null) {
            n = new Node(x, y, z);
            n.h = goal.heuristic(x, y, z);
            n.g = g;
            n.f = g + n.h * HEURISTIC_WEIGHT;
            n.parent = from;
            n.move = move;
            nodes.put(key, n);
            open.add(n);
        } else if (!n.closed && g < n.g) {
            Node copy = new Node(x, y, z); // новый объект, чтобы корректно обновить позицию в очереди
            copy.h = n.h;
            copy.g = g;
            copy.f = g + n.h * HEURISTIC_WEIGHT;
            copy.parent = from;
            copy.move = move;
            n.closed = true;
            nodes.put(key, copy);
            open.add(copy);
        }
    }

    private static void expand(Terrain t, Goal goal, Map<Long, Node> nodes, PriorityQueue<Node> open, Node n, boolean canPillar) {
        int x = n.x, y = n.y, z = n.z;
        if (!t.inBounds(y)) return;

        // ---- боковые ходы ----
        for (int[] d : CARD) {
            int nx = x + d[0], nz = z + d[1];
            if (!t.loaded(nx, nz)) continue;

            double cF = t.clearCost(nx, y, nz);
            double cH = t.clearCost(nx, y + 1, nz);

            // ровный ход
            if (cF < Terrain.INF && cH < Terrain.INF) {
                boolean inWater = t.water(nx, y, nz);
                if (t.solidTop(nx, y - 1, nz) || inWater) {
                    relax(goal, nodes, open, n, nx, y, nz, 1.0 + cF + cH + (inWater ? 1.5 : 0), Move.FLAT);
                } else {
                    // падение
                    for (int dd = 1; dd <= t.maxFall + 6; dd++) {
                        int ty = y - dd;
                        if (!t.inBounds(ty)) break;
                        if (!t.passable(nx, ty, nz)) break;
                        boolean landWater = t.water(nx, ty, nz);
                        if (landWater || t.solidTop(nx, ty - 1, nz)) {
                            if (dd <= t.maxFall || landWater) {
                                relax(goal, nodes, open, n, nx, ty, nz, 1.5 + cF + cH + dd * 0.6, Move.DESCEND);
                            }
                            break;
                        }
                    }
                }
            }

            // подъём на блок вверх
            if (t.solidTop(nx, y, nz)) {
                double cUp = t.clearCost(x, y + 2, z);
                double c1 = t.clearCost(nx, y + 1, nz);
                double c2 = t.clearCost(nx, y + 2, nz);
                if (cUp < Terrain.INF && c1 < Terrain.INF && c2 < Terrain.INF) {
                    relax(goal, nodes, open, n, nx, y + 1, nz, 2.2 + cUp + c1 + c2, Move.ASCEND);
                }
            }
        }

        // ---- диагонали (только по свободным клеткам, без ломания) ----
        for (int[] d : DIAG) {
            int nx = x + d[0], nz = z + d[1];
            if (!t.loaded(nx, nz)) continue;
            if (t.clearCost(nx, y, nz) != 0 || t.clearCost(nx, y + 1, nz) != 0) continue;
            if (t.clearCost(x + d[0], y, z) != 0 || t.clearCost(x + d[0], y + 1, z) != 0) continue;
            if (t.clearCost(x, y, z + d[1]) != 0 || t.clearCost(x, y + 1, z + d[1]) != 0) continue;
            if (!t.solidTop(nx, y - 1, nz)) continue;
            relax(goal, nodes, open, n, nx, y, nz, 1.45, Move.DIAGONAL);
        }

        // ---- копать вниз ----
        if (t.allowBreak && t.solidTop(x, y - 1, z)) {
            double cd = t.clearCost(x, y - 1, z);
            if (cd < Terrain.INF && cd > 0 && (t.solidTop(x, y - 2, z) || t.water(x, y - 2, z))) {
                relax(goal, nodes, open, n, x, y - 1, z, 1.6 + cd, Move.DIG_DOWN);
            }
        }

        // ---- столб вверх (ставим блок под себя) ----
        if (canPillar && t.solidTop(x, y - 1, z) && !t.water(x, y, z)) {
            double ch = t.clearCost(x, y + 2, z);
            if (ch < Terrain.INF) {
                relax(goal, nodes, open, n, x, y + 1, z, 4.5 + ch, Move.PILLAR);
            }
        }
    }
}

package dev.baritonestudio.path;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.task.Actor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Идёт по готовому пути, ломая блоки на пути и ставя опоры, если нужно. */
public final class PathFollower {
    public enum State { RUNNING, ARRIVED, FAILED }

    private final Path path;
    private int idx = 0;
    private int stuck = 0;
    private int pillarWait = 0;
    private Vec3d lastPos = Vec3d.ZERO;
    public String lastError = "";

    public PathFollower(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public int index() {
        return idx;
    }

    public State tick(Actor a, Terrain t) {
        ClientPlayerEntity p = a.player();
        List<BlockPos> nodes = path.nodes();
        BlockPos pb = p.getBlockPos();
        boolean grounded = p.isOnGround() || p.isTouchingWater() || p.isClimbing();

        // продвижение по узлам (допускаем перепрыгнуть до 3 узлов вперёд)
        for (int j = Math.min(nodes.size() - 1, idx + 3); j > idx; j--) {
            if (nodes.get(j).equals(pb) && (grounded || path.moves().get(j) == Move.DESCEND)) {
                idx = j;
                stuck = 0;
                pillarWait = 0;
                break;
            }
        }
        if (idx >= nodes.size() - 1) {
            if (grounded) return State.ARRIVED;
            return State.RUNNING;
        }

        BlockPos cur = nodes.get(idx);
        BlockPos next = nodes.get(idx + 1);
        Move mv = path.moves().get(idx + 1);

        // сильно сошли с маршрута
        Vec3d pos = p.getEntityPos();
        double offCourse = Math.min(horiz(pos, cur), horiz(pos, next));
        if (offCourse > 2.6 || Math.abs(pos.y - cur.getY()) > 3.5 && grounded) {
            lastError = "сошёл с маршрута";
            return State.FAILED;
        }

        // 1) освобождаем клетки, мешающие шагу
        for (BlockPos c : cellsToClear(cur, next, mv)) {
            if (!t.passable(c.getX(), c.getY(), c.getZ())) {
                if (!t.breakable(c.getX(), c.getY(), c.getZ())) {
                    lastError = "путь заблокирован";
                    return State.FAILED;
                }
                Actor.MineResult r = a.mine(c);
                if (r == Actor.MineResult.FAIL) {
                    lastError = "не дотянуться до блока";
                    return State.FAILED;
                }
                stuck = 0;
                return State.RUNNING;
            }
        }

        // 2) движение
        Vec3d dest = new Vec3d(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
        double hd = horiz(pos, next);
        boolean inWater = p.isTouchingWater();

        switch (mv) {
            case PILLAR -> {
                a.lookAngles(p.getYaw(), 90f);
                double cx = horiz(pos, cur);
                if (cx > 0.2) {
                    a.lookAt(dest);
                    a.forward(true);
                }
                if (a.selectItem(Actor::isScaffold)) {
                    a.jump(true);
                    boolean airborne = pos.y - cur.getY() > 0.2;
                    if (airborne && t.passable(cur.getX(), cur.getY(), cur.getZ())) {
                        ActionResult r = a.placeAgainst(cur.down(), Direction.UP);
                        if (r.isAccepted()) pillarWait = 0;
                    }
                    if (++pillarWait > 120) {
                        lastError = "не удалось поставить опору";
                        return State.FAILED;
                    }
                } else {
                    lastError = "нет блоков для опоры";
                    return State.FAILED;
                }
                return State.RUNNING;
            }
            case DIG_DOWN -> {
                if (hd > 0.15) {
                    a.lookAt(dest);
                    a.forward(true);
                } else {
                    a.lookAngles(p.getYaw(), 60f);
                }
            }
            default -> {
                float rem = a.lookAt(new Vec3d(dest.x, p.getEyeY(), dest.z));
                boolean stopForward = mv == Move.DESCEND && p.getBlockX() == next.getX() && p.getBlockZ() == next.getZ() && !grounded;
                if (!stopForward && rem < 60f) {
                    a.forward(true);
                    boolean straight = mv == Move.FLAT || mv == Move.DIAGONAL;
                    if (ModConfig.get().sprint && straight && !inWater && p.getHungerManager().canSprint()) a.sprint(true);
                }
                if (mv == Move.ASCEND && grounded && hd < 1.45) a.jump(true);
                if (inWater && dest.y >= pos.y - 0.3) a.jump(true);
                if (p.horizontalCollision && grounded && mv != Move.DESCEND) a.jump(true);
            }
        }

        // 3) застревание
        if (pos.squaredDistanceTo(lastPos) < 0.0009) {
            if (++stuck > 60) {
                lastError = "застрял";
                return State.FAILED;
            }
        } else {
            stuck = 0;
            lastPos = pos;
        }
        return State.RUNNING;
    }

    private static double horiz(Vec3d p, BlockPos b) {
        double dx = p.x - (b.getX() + 0.5), dz = p.z - (b.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static List<BlockPos> cellsToClear(BlockPos cur, BlockPos next, Move mv) {
        List<BlockPos> l = new ArrayList<>(3);
        switch (mv) {
            case FLAT, DESCEND -> {
                l.add(next.up());
                l.add(next);
            }
            case ASCEND -> {
                l.add(cur.up(2));
                l.add(next.up());
                l.add(next);
            }
            case DIG_DOWN -> l.add(next);
            case PILLAR -> l.add(cur.up(2));
            default -> {}
        }
        return l;
    }
}

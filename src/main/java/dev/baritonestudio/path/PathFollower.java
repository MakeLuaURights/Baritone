package dev.baritonestudio.path;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.task.Actor;
import dev.baritonestudio.task.Human;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.BlockState;
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
    private int doorTicks = 0;
    private int pillarWait = 0;
    private int mineTicks = 0;
    private int moveTicks = 0;
    private int awayTicks = 0;
    private int chunkWait = 0;
    private boolean jumped;
    private int jumpIdx = -1;
    private double jumpAt = 1.45;
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
                mineTicks = 0;
                stuck = 0;
                pillarWait = 0;
                doorTicks = 0;
                moveTicks = 0;
                awayTicks = 0;
                jumped = false;
                break;
            }
        }
        // отбросило назад (удар, телепорт): возвращаемся к соответствующему узлу
        for (int j = Math.max(0, idx - 6); j < idx; j++) {
            if (nodes.get(j).equals(pb) && grounded) {
                idx = j;
                moveTicks = 0;
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

        // сошли с маршрута: >3 блоков – сразу, >2 блоков дольше 10 секунд – тоже (пороги из Baritone)
        Vec3d pos = p.getEntityPos();
        double offCourse = distToSegment(pos, cur, next);
        if (offCourse > 3.0 || Math.abs(pos.y - cur.getY()) > 4.0 && grounded) {
            lastError = "сошёл с маршрута";
            return State.FAILED;
        }
        if (offCourse > 2.0) {
            if (++awayTicks > 200) {
                lastError = "слишком долго вне маршрута";
                return State.FAILED;
            }
        } else {
            awayTicks = 0;
        }

        // не двигаемся, пока не загрузился чанк, в который собираемся идти
        if (!t.loaded(next.getX(), next.getZ())) {
            if (++chunkWait > 200) {
                lastError = "чанк не загрузился";
                return State.FAILED;
            }
            return State.RUNNING;
        }
        chunkWait = 0;

        // таймаут хода: он не должен занимать намного дольше, чем рассчитано
        if (++moveTicks > 260 && mv != Move.PILLAR) {
            lastError = "ход занял слишком много времени";
            return State.FAILED;
        }

        // 1) освобождаем клетки, мешающие шагу
        for (BlockPos c : cellsToClear(cur, next, mv)) {
            BlockState ds = a.world().getBlockState(c);
            if (Terrain.openable(ds) && !ds.getCollisionShape(a.world(), c).isEmpty()) {
                // закрытая дверь/калитка на пути – открываем
                if (++doorTicks > 60) {
                    lastError = "не открыть дверь";
                    return State.FAILED;
                }
                Vec3d cc = Vec3d.ofCenter(c);
                a.lookAt(cc);
                if (doorTicks % 6 == 1 && a.eyeDistance(c) <= 4.5) {
                    a.useOn(new net.minecraft.util.hit.BlockHitResult(cc, Direction.UP, c, false));
                } else if (a.eyeDistance(c) > 2.0) {
                    a.forward(true);
                }
                return State.RUNNING;
            }
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
                if (r == Actor.MineResult.DONE) continue; // уже сломан (или жидкость) – проверяем следующую клетку
                if (++mineTicks > 400) {
                    lastError = "слишком долго ломаю блок на пути";
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
                if (a.selectScaffold()) {
                    a.jump(true);
                    boolean airborne = pos.y - cur.getY() > 0.2;
                    if (airborne && t.passable(cur.getX(), cur.getY(), cur.getZ())) {
                        ActionResult r = a.placeAgainst(cur.down(), Direction.UP);
                        if (r.isAccepted()) {
                            pillarWait = 0;
                            a.placedScaffold.add(cur.toImmutable());
                        }
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
            case PARKOUR -> {
                // разбег по прямой и прыжок с края; в воздухе держим курс на точку приземления
                int dx = Integer.signum(next.getX() - cur.getX()), dz = Integer.signum(next.getZ() - cur.getZ());
                double along = (pos.x - (cur.getX() + 0.5)) * dx + (pos.z - (cur.getZ() + 0.5)) * dz;
                a.lookAt(new Vec3d(dest.x, p.getEyeY(), dest.z));
                a.forward(true);
                a.sprint(true);
                if (!jumped && p.isOnGround() && along >= 0.38) {
                    a.jump(true);
                    jumped = true;
                }
                if (jumped && p.isOnGround() && !nodes.get(idx).equals(pb) && pos.y < cur.getY() - 0.2) {
                    lastError = "не допрыгнул";
                    return State.FAILED;
                }
                if (pos.y < cur.getY() - 1.6) {
                    lastError = "упал при прыжке";
                    return State.FAILED;
                }
                if (jumped && p.isOnGround() && along > 0.5 && hd > 1.2 && pos.y <= cur.getY() + 0.01) jumped = false; // приземлились раньше – можно повторить
                return State.RUNNING;
            }
            case CLIMB_UP -> {
                a.lookAt(new Vec3d(dest.x, p.getEyeY(), dest.z));
                if (hd > 0.3) a.forward(true);
                a.jump(true);
            }
            case CLIMB_DOWN -> {
                a.lookAt(new Vec3d(dest.x, p.getEyeY(), dest.z));
                if (hd > 0.3) a.forward(true);
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
                Human h = Human.get();
                boolean straight = mv == Move.FLAT || mv == Move.DIAGONAL;
                boolean pause = h.walkPause(grounded && straight && !inWater);
                if (jumpIdx != idx) {
                    jumpIdx = idx;
                    jumpAt = h.jumpDistance();
                }
                Vec3d lookDest = new Vec3d(dest.x, p.getEyeY(), dest.z);
                float rem;
                if (pause && (h.glanceYaw() != 0 || h.glancePitch() != 0)) {
                    rem = a.lookAngles(a.yawTo(lookDest) + h.glanceYaw(), 4f + h.glancePitch());
                    rem = 0; // осматриваемся – к цели возвращаемся плавно после паузы
                } else {
                    rem = a.lookAt(lookDest);
                }
                boolean stopForward = mv == Move.DESCEND && p.getBlockX() == next.getX() && p.getBlockZ() == next.getZ() && !grounded;
                boolean landedOnDest = (mv == Move.ASCEND || mv == Move.DESCEND) && grounded && hd < 0.3 && Math.abs(pos.y - next.getY()) < 0.3;
                if (!stopForward && !pause && !landedOnDest && rem < 60f) {
                    a.forward(true);
                    boolean last = idx + 2 >= nodes.size();
                    boolean turnAhead = idx + 2 < nodes.size() && hd < 2.3 && turnsAfter(nodes.get(idx), next, nodes.get(idx + 2));
                    // в прыжке на ступеньку бежим только если дальше путь продолжается прямо – иначе перелетим цель
                    boolean ascendChain = mv == Move.ASCEND && !last && !turnAhead && path.moves().get(idx + 2) != Move.DESCEND;
                    boolean endingSoon = last && hd < 2.0;
                    if (ModConfig.get().sprint && (straight || ascendChain) && !inWater && !(h.on() && turnAhead) && !endingSoon && p.getHungerManager().canSprint() && h.sprintAllowed()) a.sprint(true);
                    // лёгкое «виляние» вбок, если по бокам твёрдая земля
                    int sd = h.strafe(straight && grounded && !inWater && hd > 1.5);
                    if (sd != 0) {
                        double yr = Math.toRadians(p.getYaw());
                        double lx = Math.cos(yr) * sd, lz = Math.sin(yr) * sd; // sd=1 – влево
                        int bx = (int) Math.floor(pos.x + lx * 0.9), bz = (int) Math.floor(pos.z + lz * 0.9);
                        int by = p.getBlockY();
                        if (t.solidTop(bx, by - 1, bz) && t.passable(bx, by, bz) && t.passable(bx, by + 1, bz)) {
                            if (sd > 0) a.left(true);
                            else a.right(true);
                        }
                    }
                }
                if (pause) stuck = 0;
                if (mv == Move.ASCEND && grounded && hd < jumpAt) a.jump(true);
                if (inWater && dest.y >= pos.y - 0.3) a.jump(true);
                if (p.horizontalCollision && grounded && mv != Move.DESCEND) a.jump(true);
            }
        }

        // 3) застревание
        if (pos.squaredDistanceTo(lastPos) < 0.0009) {
            // лёгкая «встряска»: прыжок и шаг вбок, прежде чем сдаться
            if (stuck > 20 && grounded && mv != Move.PILLAR && mv != Move.DIG_DOWN) {
                a.jump(true);
                if ((stuck / 8) % 2 == 0) a.left(true);
                else a.right(true);
            }
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

    /** Расстояние от точки до отрезка между центрами узлов (в горизонтали). */
    private static double distToSegment(Vec3d p, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5, az = a.getZ() + 0.5, bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((p.x - ax) * dx + (p.z - az) * dz) / len2));
        double cx = ax + dx * t, cz = az + dz * t;
        return Math.sqrt((p.x - cx) * (p.x - cx) + (p.z - cz) * (p.z - cz));
    }

    private static boolean turnsAfter(BlockPos a, BlockPos b, BlockPos c) {
        return Integer.signum(b.getX() - a.getX()) != Integer.signum(c.getX() - b.getX())
                || Integer.signum(b.getZ() - a.getZ()) != Integer.signum(c.getZ() - b.getZ());
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

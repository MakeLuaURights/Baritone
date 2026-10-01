package dev.baritonestudio.task;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.util.L;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Заполнить область блоками снизу вверх (бот стоит снаружи области). */
public final class FillAreaTask extends Task {
    private final BlockPos min, max;
    private final Item item;
    private final Navigator nav = new Navigator();
    private final Set<BlockPos> bad = new HashSet<>();
    private BlockPos cell, support;
    private Direction face;
    private int placed, ticks, attempts, align, itemWait, verifyAt;
    private boolean clicked, navigating;
    private String status = "";

    public FillAreaTask(BlockPos a, BlockPos b, Item item) {
        this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.item = item;
    }

    private boolean inBox(int x, int y, int z) {
        return x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY() && z >= min.getZ() && z <= max.getZ();
    }

    @Override
    public String name() {
        return L.t("task.fill", item.getName().getString());
    }

    @Override
    public String status() {
        return L.t("status.filling", placed) + " • " + status;
    }

    @Override
    public Navigator navigator() {
        return nav;
    }

    @Override
    public void start(Actor a) {
        nav.breakMode = Navigator.BreakMode.NEVER;
    }

    @Override
    public void stop(Actor a) {
        nav.clear();
    }

    private boolean fillable(BlockState s) {
        return s.isAir() || (s.isReplaceable() && !s.isLiquid());
    }

    /** Ищет грань соседнего твёрдого блока, на которую можно поставить блок в клетку. */
    private boolean findSupport(Actor a, BlockPos c) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos n = c.offset(d);
            BlockState st = a.world().getBlockState(n);
            if (!st.isAir() && !st.isReplaceable() && !st.isLiquid() && st.isSideSolidFullSquare(a.world(), n, d.getOpposite())) {
                support = n;
                face = d.getOpposite();
                return true;
            }
        }
        return false;
    }

    private boolean pickCell(Actor a) {
        BlockPos me = a.player().getBlockPos();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        int unsupported = 0;
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos c = new BlockPos(x, y, z);
                    if (bad.contains(c) || !fillable(a.world().getBlockState(c))) continue;
                    if (!findSupport(a, c)) {
                        unsupported++;
                        continue;
                    }
                    double d = y * 10000.0 + c.getSquaredDistance(me);
                    if (d < bd) bd = d;
                    if (best == null || d <= bd) best = c;
                }
            }
            if (best != null) break; // нижний слой в приоритете
        }
        if (best == null) {
            if (unsupported == 0) finish(L.t("status.fill_done", placed));
            else finish(L.t("status.fill_no_support", placed, unsupported));
            return false;
        }
        cell = best;
        findSupport(a, cell);
        attempts = 0;
        align = 0;
        clicked = false;
        navigating = false;
        ticks = 0;
        return true;
    }

    private boolean reachable(int x, int y, int z) {
        if (inBox(x, y, z) || inBox(x, y + 1, z)) return false;
        Vec3d eye = new Vec3d(x + 0.5, y + 1.62, z + 0.5);
        return eye.distanceTo(Vec3d.ofCenter(cell)) <= ModConfig.get().reach - 0.2;
    }

    @Override
    public void tick(Actor a) {
        if (!a.selectItem(st -> st.isOf(item))) {
            if (++itemWait > ModConfig.get().itemWaitSeconds * 20) fail(L.t("status.no_item", item.getName().getString()));
            status = L.t("status.waiting_item", item.getName().getString());
            return;
        }
        itemWait = 0;
        if (cell == null && !pickCell(a)) return;
        if (!fillable(a.world().getBlockState(cell))) {
            if (clicked) placed++;
            cell = null;
            return;
        }
        if (++ticks > 600) {
            bad.add(cell);
            cell = null;
            return;
        }
        BlockPos c = cell;
        var pb = a.player().getBlockPos();
        if (!reachable(pb.getX(), pb.getY(), pb.getZ()) || !a.player().isOnGround()) {
            if (!navigating) {
                nav.setGoal(new Goal.Custom(this::reachableGoal, (x, y, z) -> Math.max(0, Goal.octile(c.getX() - x, c.getZ() - z) - 3 + Math.abs(c.getY() - y) * 0.7)));
                navigating = true;
            }
            status = L.t("status.walking");
            Navigator.Status st = nav.tick(a);
            if (st == Navigator.Status.FAILED) {
                bad.add(cell);
                cell = null;
            }
            return;
        }
        navigating = false;
        nav.clear();
        status = L.t("status.placing", item.getName().getString());
        Vec3d hit = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        if (!clicked) {
            float rem = a.lookAt(Human.get().aimPoint(hit, c.asLong()));
            if (rem > 3f) {
                align = 0;
                return;
            }
            if (++align < Human.get().jitter(2)) return;
            a.placeAgainst(support, face);
            clicked = true;
            verifyAt = ticks + 4 + a.latencyTicks();
            return;
        }
        if (ticks < verifyAt) return;
        if (!fillable(a.world().getBlockState(c))) {
            placed++;
            cell = null;
        } else if (++attempts >= 3) {
            bad.add(c);
            cell = null;
        } else {
            clicked = false;
            align = 0;
        }
    }

    private boolean reachableGoal(int x, int y, int z) {
        return reachable(x, y, z);
    }
}

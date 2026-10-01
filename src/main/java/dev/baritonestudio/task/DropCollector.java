package dev.baritonestudio.task;

import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Подбирает выпавшие предметы: рядом — идёт на них напрямую (как игрок), издали — по пути навигатора,
 * и всегда подходит вплотную (радиус подбора около 1.3 блока).
 */
public final class DropCollector {
    private final Navigator nav;
    private final Set<Integer> skipped = new HashSet<>();
    private ItemEntity target;
    private int ticks, reaction, noProgress, stillTicks;
    private double lastDist;
    private boolean directFailed, navStarted, triedNear;
    private final double radius;

    public DropCollector(Navigator nav, double radius) {
        this.nav = nav;
        this.radius = radius;
    }

    public void reset() {
        skipped.clear();
        target = null;
        nav.clear();
    }

    private void drop(boolean skip) {
        if (skip && target != null) skipped.add(target.getId());
        target = null;
        nav.clear();
    }

    /** @return true, когда подбирать больше нечего. */
    public boolean tick(Actor a) {
        ClientPlayerEntity p = a.player();
        if (target != null && (target.isRemoved() || !target.isAlive())) drop(false);
        if (target == null) {
            List<ItemEntity> items = a.world().getEntitiesByClass(ItemEntity.class, new Box(p.getBlockPos()).expand(radius),
                    e -> !skipped.contains(e.getId()) && e.isAlive());
            ItemEntity best = null;
            double bd = Double.MAX_VALUE;
            for (ItemEntity e : items) {
                double dy = Math.abs(e.getY() - p.getY());
                double d = e.squaredDistanceTo(p) + dy * dy * 2;
                if (d < bd) {
                    bd = d;
                    best = e;
                }
            }
            if (best == null) return true;
            target = best;
            ticks = 0;
            noProgress = 0;
            stillTicks = 0;
            lastDist = Double.MAX_VALUE;
            directFailed = false;
            navStarted = false;
            triedNear = false;
            reaction = Human.get().on() ? Human.get().reaction() / 2 : 0;
            dev.baritonestudio.util.Storage.LOG.info("DropCollector: предмет {} в {} {} {}", best.getStack(), best.getBlockX(), best.getBlockY(), best.getBlockZ());
        }
        Vec3d ip = target.getEntityPos();
        if (reaction > 0) {
            reaction--;
            a.lookAt(ip);
            return false;
        }
        if (++ticks > 140) {
            drop(true);
            return false;
        }

        double dx = ip.x - p.getX(), dz = ip.z - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        double dy = ip.y - p.getY();

        // рядом и примерно на одном уровне: идём прямо к предмету
        if (!directFailed && hd <= 3.4 && dy > -1.6 && dy < 1.3) {
            nav.clear();
            navStarted = false;
            a.lookAt(ip.add(0, 0.2, 0));
            if (hd > 0.25) {
                a.forward(true);
                if (p.horizontalCollision && p.isOnGround()) a.jump(true);
            }
            double dist = Math.sqrt(hd * hd + dy * dy);
            if (dist < lastDist - 0.03) {
                lastDist = dist;
                noProgress = 0;
            } else if (++noProgress > 18) {
                directFailed = true; // упёрлись – пусть ведёт навигатор
            }
            if (hd <= 0.25 && ++stillTicks > 30) drop(true);
            return false;
        }

        if (!navStarted) {
            navStarted = true;
            nav.setGoal(triedNear
                    ? new Goal.Near(target.getBlockX(), target.getBlockY(), target.getBlockZ(), 1)
                    : new Goal.Block(target.getBlockX(), target.getBlockY(), target.getBlockZ()));
        }
        Navigator.Status s = nav.tick(a);
        if (s == Navigator.Status.FAILED) {
            if (!triedNear) {
                triedNear = true;
                navStarted = false;
            } else {
                drop(true);
            }
        } else if (s == Navigator.Status.ARRIVED) {
            directFailed = false;
            noProgress = 0;
            if (++stillTicks > 40) drop(true);
        }
        return false;
    }
}

package dev.baritonestudio.task;

import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;

/** Подбирает выпавшие предметы поблизости. */
public final class DropCollector {
    private final Navigator nav;
    private final Set<Integer> skipped = new HashSet<>();
    private ItemEntity target;
    private int ticks;
    private boolean precise;
    private int arrivedTicks;
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

    /** @return true, когда подбирать больше нечего. */
    public boolean tick(Actor a) {
        ClientPlayerEntity p = a.player();
        if (target != null && (target.isRemoved() || !target.isAlive())) {
            target = null;
            nav.clear();
        }
        if (target == null) {
            List<ItemEntity> items = a.world().getEntitiesByClass(ItemEntity.class, new Box(p.getBlockPos()).expand(radius), e -> !skipped.contains(e.getId()) && e.isAlive());
            ItemEntity best = null;
            double bd = Double.MAX_VALUE;
            for (ItemEntity e : items) {
                double d = e.squaredDistanceTo(p);
                if (d < bd) {
                    bd = d;
                    best = e;
                }
            }
            if (best == null) return true;
            target = best;
            ticks = 0;
            precise = false;
            arrivedTicks = 0;
            dev.baritonestudio.util.Storage.LOG.info("DropCollector: предмет {} в {} {} {}", best.getStack(), best.getBlockX(), best.getBlockY(), best.getBlockZ());
            nav.setGoal(new Goal.Near(best.getBlockX(), best.getBlockY(), best.getBlockZ(), 1));
        }
        if (++ticks > 120) {
            skipped.add(target.getId());
            target = null;
            nav.clear();
            return false;
        }
        Navigator.Status s = nav.tick(a);
        if (s == Navigator.Status.FAILED) {
            if (!precise) {
                // не удалось подойти «примерно» – пробуем встать в клетку предмета
                precise = true;
                nav.setGoal(new Goal.Block(target.getBlockX(), target.getBlockY(), target.getBlockZ()));
            } else {
                skipped.add(target.getId());
                target = null;
                nav.clear();
            }
        } else if (s == Navigator.Status.ARRIVED) {
            // дошли, но предмет всё ещё лежит: радиус подбора ~1.3 блока, подходим вплотную
            if (!precise && ticks > 12) {
                precise = true;
                arrivedTicks = 0;
                nav.setGoal(new Goal.Block(target.getBlockX(), target.getBlockY(), target.getBlockZ()));
            } else if (precise && ++arrivedTicks > 25) {
                skipped.add(target.getId());
                target = null;
                nav.clear();
            }
        }
        return false;
    }
}

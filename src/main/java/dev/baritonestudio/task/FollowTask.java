package dev.baritonestudio.task;

import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.util.L;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Следовать за игроком (по нику) или за ближайшим существом (по названию вида, например pig). */
public final class FollowTask extends Task {
    private final String query;
    private final int range;
    private final Navigator nav = new Navigator();
    private Entity target;
    private BlockPos goalPos;
    private int lost;
    private int recheck;
    private String status = "";

    public FollowTask(String query, int range) {
        this.query = query == null ? "" : query.trim();
        this.range = Math.max(2, range);
    }

    @Override
    public String name() {
        return query.isEmpty() ? L.t("task.follow_any") : L.t("task.follow", query);
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public Navigator navigator() {
        return nav;
    }

    @Override
    public void start(Actor a) {
        nav.breakMode = Navigator.BreakMode.CONFIG;
    }

    @Override
    public void stop(Actor a) {
        nav.clear();
    }

    private Entity find(Actor a) {
        var me = a.player();
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (AbstractClientPlayerEntity p : a.world().getPlayers()) {
            if (p == me || p.isSpectator()) continue;
            if (!query.isEmpty() && !p.getGameProfile().name().equalsIgnoreCase(query)) continue;
            double d = p.squaredDistanceTo(me);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best != null || query.isEmpty()) return best;
        String q = query.toLowerCase();
        for (Entity e : a.world().getOtherEntities(me, new Box(me.getBlockPos()).expand(96), x -> x instanceof LivingEntity && x.isAlive())) {
            String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            if (!id.equals(q) && !id.contains(q) && !e.getName().getString().equalsIgnoreCase(query)) continue;
            double d = e.squaredDistanceTo(me);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    public void tick(Actor a) {
        if (target == null || !target.isAlive() || target.isRemoved() || --recheck <= 0) {
            recheck = 20;
            Entity f = find(a);
            if (f != null) target = f;
            else if (target != null && (!target.isAlive() || target.isRemoved())) target = null;
        }
        if (target == null) {
            status = L.t("status.follow_search");
            if (++lost > 400) finish(L.t("status.follow_lost"));
            return;
        }
        lost = 0;
        var me = a.player();
        double dist = me.distanceTo(target);
        status = L.t("status.following", target.getName().getString(), Math.round(dist));
        BlockPos tp = target.getBlockPos();
        if (dist > range + 1.5 && (goalPos == null || goalPos.getSquaredDistance(tp) > 6)) {
            goalPos = tp;
            nav.setGoal(new Goal.Near(tp.getX(), tp.getY(), tp.getZ(), range));
        }
        if (nav.goal() != null) {
            var st = nav.tick(a);
            if (st == Navigator.Status.ARRIVED || st == Navigator.Status.FAILED) {
                if (st == Navigator.Status.FAILED) goalPos = null;
                nav.clear();
            }
        }
        if (dist <= range + 3) a.lookAt(target.getEyePos());
    }
}

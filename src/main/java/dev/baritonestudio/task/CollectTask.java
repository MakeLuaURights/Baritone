package dev.baritonestudio.task;

import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.util.L;

/** Собрать все выпавшие предметы вокруг. */
public final class CollectTask extends Task {
    private final Navigator nav = new Navigator();
    private final DropCollector col;
    private int collectedPhases;

    public CollectTask(int radius) {
        this.col = new DropCollector(nav, radius);
    }

    @Override
    public String name() {
        return L.t("task.collect");
    }

    @Override
    public String status() {
        return L.t("status.collecting");
    }

    @Override
    public void start(Actor a) {
        col.reset();
    }

    @Override
    public void tick(Actor a) {
        if (col.tick(a)) {
            finish(L.t("status.collect_done"));
        }
    }

    @Override
    public void stop(Actor a) {
        nav.clear();
    }

    @Override
    public Navigator navigator() {
        return nav;
    }
}

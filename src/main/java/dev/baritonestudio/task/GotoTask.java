package dev.baritonestudio.task;

import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.util.L;

/** Дойти до цели (точка, уровень Y и т. п.). */
public final class GotoTask extends Task {
    private final String title;
    private final Goal goal;
    private final boolean forceBreak;
    private final Navigator nav = new Navigator();

    public GotoTask(String title, Goal goal, boolean forceBreak) {
        this.title = title;
        this.goal = goal;
        this.forceBreak = forceBreak;
    }

    @Override
    public String name() {
        return title;
    }

    @Override
    public String status() {
        return L.t("status.walking");
    }

    @Override
    public void start(Actor a) {
        nav.breakMode = forceBreak ? Navigator.BreakMode.ALWAYS : Navigator.BreakMode.CONFIG;
        nav.setGoal(goal);
    }

    @Override
    public void tick(Actor a) {
        switch (nav.tick(a)) {
            case ARRIVED -> finish(L.t("status.arrived"));
            case FAILED -> fail(nav.error);
            default -> {}
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

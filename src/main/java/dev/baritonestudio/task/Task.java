package dev.baritonestudio.task;

import dev.baritonestudio.path.Navigator;

/** Долгая автоматическая работа, которой управляет {@link TaskManager}. */
public abstract class Task {
    private boolean done;
    private boolean failed;
    private String result = "";

    public abstract String name();

    /** Что делает задача прямо сейчас (для панели состояния). */
    public abstract String status();

    public abstract void start(Actor a);

    public abstract void tick(Actor a);

    public void stop(Actor a) {}

    /** Прогресс 0..1 или -1, если неизвестен. */
    public double progress() {
        return -1;
    }

    /** Навигатор для показа маршрута на экране (может быть null). */
    public Navigator navigator() {
        return null;
    }

    protected final void finish(String msg) {
        done = true;
        result = msg;
    }

    protected final void fail(String msg) {
        done = true;
        failed = true;
        result = msg;
    }

    public final boolean isDone() {
        return done;
    }

    public final boolean isFailed() {
        return failed;
    }

    public final String result() {
        return result;
    }
}

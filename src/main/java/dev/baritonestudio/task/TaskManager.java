package dev.baritonestudio.task;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.util.L;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Запускает одну активную задачу и следит за безопасностью. */
public final class TaskManager {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Actor actor = new Actor();
    private final AutoEat eat = new AutoEat();
    private Task current;
    private Task last;
    private long startedAt;

    public Actor actor() {
        return actor;
    }

    public Task current() {
        return current;
    }

    public Task last() {
        return last;
    }

    public long elapsedTicks() {
        return current == null ? 0 : (mc.world != null ? mc.world.getTime() - startedAt : 0);
    }

    public boolean busy() {
        return current != null;
    }

    public void start(Task t) {
        if (mc.player == null || mc.world == null) return;
        stop(null);
        current = t;
        last = t;
        startedAt = mc.world.getTime();
        // путь строится для хождения по земле – выключаем полёт (креатив)
        if (mc.player.getAbilities().flying) {
            mc.player.getAbilities().flying = false;
            mc.player.sendAbilitiesUpdate();
        }
        t.start(actor);
        say(Formatting.AQUA, L.t("msg.started", t.name()));
        if (t.isDone()) finishCurrent();
    }

    /** Остановить текущую задачу. reason == null — без сообщения. */
    public void stop(String reason) {
        if (current == null) return;
        current.stop(actor);
        actor.releaseKeys();
        current = null;
        if (reason != null) say(Formatting.YELLOW, reason);
    }

    public void tick() {
        ClientPlayerEntity p = mc.player;
        if (current == null) {
            actor.apply();
            return;
        }
        if (p == null || mc.world == null) {
            current.stop(actor);
            current = null;
            return;
        }
        if (!p.isAlive()) {
            stop(L.t("msg.died"));
            return;
        }
        int hp = ModConfig.get().stopHealth;
        if (hp > 0 && p.getHealth() <= hp && !p.isCreative()) {
            stop(L.t("msg.low_health", Math.round(p.getHealth() / 2f * 10) / 10.0));
            return;
        }
        if (eat.tick(actor)) {
            actor.apply();
            return;
        }
        try {
            current.tick(actor);
        } catch (RuntimeException e) {
            dev.baritonestudio.util.Storage.LOG.error("Ошибка в задаче", e);
            current.stop(actor);
            actor.releaseKeys();
            say(Formatting.RED, L.t("msg.crashed", String.valueOf(e)));
            current = null;
            return;
        }
        if (current != null && current.isDone()) {
            finishCurrent();
        } else {
            actor.apply();
        }
    }

    private void finishCurrent() {
        Task t = current;
        t.stop(actor);
        actor.releaseKeys();
        current = null;
        dev.baritonestudio.util.Storage.LOG.info("Задача «{}» завершена за {} тиков", t.name(), mc.world != null ? mc.world.getTime() - startedAt : -1);
        if (t.isFailed()) say(Formatting.RED, L.t("msg.failed", t.name(), t.result()));
        else say(Formatting.GREEN, L.t("msg.finished", t.name(), t.result()));
    }

    public void say(Formatting color, String msg) {
        dev.baritonestudio.util.Storage.LOG.info("[chat] {}", msg);
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Baritone Studio] ").formatted(Formatting.DARK_AQUA).append(Text.literal(msg).formatted(color)), false);
        }
    }
}

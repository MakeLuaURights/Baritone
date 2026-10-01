package dev.baritonestudio.gui;

import dev.baritonestudio.Studio;
import dev.baritonestudio.gui.StudioScreen.Style;
import dev.baritonestudio.task.Task;
import dev.baritonestudio.util.L;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/** Вкладка «Статус»: что делает бот сейчас и ручной переход к точке. */
final class StatusTab {
    private final StudioScreen s;

    StatusTab(StudioScreen s) {
        this.s = s;
    }

    void render() {
        Studio st = Studio.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        int x = s.cx, y = s.cy, w = s.cw;

        Ui.round(s.g, x, y, w, 78, 0xFF181E25);
        Task t = st.tasks.current();
        if (t != null) {
            s.textShadow("▶ " + s.trim(t.name(), w - 20), x + 10, y + 8, Ui.ACCENT);
            s.text(s.trim(t.status(), w - 20), x + 10, y + 22, Ui.TEXT);
            double pr = t.progress();
            if (pr >= 0) {
                Ui.progress(s.g, x + 10, y + 38, w - 20, 6, pr);
                s.text(Math.round(pr * 100) + "%", x + 10, y + 48, Ui.DIM);
            }
            long sec = st.tasks.elapsedTicks() / 20;
            s.text(L.t("status.elapsed", sec / 60, String.format("%02d", sec % 60)), x + w - 90, y + 48, Ui.DIM);
            s.button(x + 10, y + 58, w - 20, 16, L.t("ui.stop"), Style.DANGER, true, () -> st.stopAll(L.t("msg.stopped")));
        } else {
            s.textShadow(L.t("status.idle"), x + 10, y + 8, Ui.TEXT);
            Task last = st.tasks.last();
            if (last != null) {
                String res = (last.isFailed() ? "✖ " : "✔ ") + last.name() + ": " + last.result();
                s.wrapped(res, x + 10, y + 24, w - 20, last.isFailed() ? Ui.DANGER : Ui.OK, 3);
            } else {
                s.wrapped(L.t("status.idle_hint"), x + 10, y + 24, w - 20, Ui.DIM, 3);
            }
        }

        // ---- переход к точке
        y += 88;
        Ui.round(s.g, x, y, w, 84, 0xFF181E25);
        s.textShadow(L.t("status.goto"), x + 10, y + 8, Ui.TEXT);
        BlockPos pp = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
        int fw = Math.min(60, (w - 40) / 3);
        s.field("go.x", x + 10, y + 24, fw, 16, "X", Integer.toString(pp.getX()));
        s.field("go.y", x + 14 + fw, y + 24, fw, 16, "Y", Integer.toString(pp.getY()));
        s.field("go.z", x + 18 + 2 * fw, y + 24, fw, 16, "Z", Integer.toString(pp.getZ()));
        int bx = x + 22 + 3 * fw;
        s.button(bx, y + 24, Math.max(40, x + w - 10 - bx), 16, "▶ " + L.t("status.go"), Style.ACCENT, true, () -> {
            mc.setScreen(null);
            st.startGoto(s.intField("go.x", pp.getX()), s.intField("go.y", pp.getY()), s.intField("go.z", pp.getZ()));
        });
        int hw = (w - 24) / 2;
        s.button(x + 10, y + 46, hw, 16, L.t("status.from_crosshair"), Style.NORMAL, true, () -> {
            if (mc.getCameraEntity() != null) {
                HitResult hr = mc.getCameraEntity().raycast(96.0, 0f, false);
                if (hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK) {
                    BlockPos p = b.getBlockPos().up();
                    s.setField("go.x", Integer.toString(p.getX()));
                    s.setField("go.y", Integer.toString(p.getY()));
                    s.setField("go.z", Integer.toString(p.getZ()));
                }
            }
        });
        s.button(x + 14 + hw, y + 46, hw, 16, L.t("status.my_pos"), Style.NORMAL, true, () -> {
            s.setField("go.x", Integer.toString(pp.getX()));
            s.setField("go.y", Integer.toString(pp.getY()));
            s.setField("go.z", Integer.toString(pp.getZ()));
        });
        s.text(L.t("status.goto_hint"), x + 10, y + 68, Ui.DIM);
    }
}

package dev.baritonestudio.gui;

import dev.baritonestudio.Keys;
import dev.baritonestudio.Studio;
import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.task.Task;
import dev.baritonestudio.util.L;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

/** Небольшая панель состояния в углу экрана. */
public final class Hud {
    private Hud() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.of("baritonestudio", "status"), Hud::render);
    }

    private static void render(DrawContext g, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || !ModConfig.get().showHud) return;
        Studio st = Studio.get();
        TextRenderer tr = mc.textRenderer;
        int x = 6, y = 6;

        Task t = st.tasks.current();
        if (st.recorder.active()) {
            int pulse = (int) (System.currentTimeMillis() / 500 % 2);
            line(g, tr, x + 12, y, L.t("hud.recording", st.recorder.actionCount(), Keys.RECORD.getBoundKeyLocalizedText().getString()), 0xFFFF5555);
            g.fill(x, y + 1, x + 8, y + 9, pulse == 0 ? 0xFFFF4444 : 0xFF7A2A2A);
            y += 12;
        }
        if (t != null) {
            line(g, tr, x, y, t.name(), Ui.ACCENT);
            y += 12;
            line(g, tr, x, y, t.status(), 0xFFDDE6EE);
            y += 12;
            double pr = t.progress();
            if (pr >= 0) {
                Ui.progress(g, x, y + 1, 150, 5, pr);
                y += 10;
            }
            line(g, tr, x, y, L.t("hud.stop_hint", Keys.STOP.getBoundKeyLocalizedText().getString()), 0xFF8B98A5);
            y += 12;
        }
        if (st.selecting) {
            line(g, tr, x, y, L.t("hud.selecting", st.selA == null ? 1 : 2), 0xFF4DE3E3);
            y += 12;
            line(g, tr, x, y, L.t("hud.selecting_keys", Keys.TARGET_ADD.getBoundKeyLocalizedText().getString(),
                    Keys.TARGET_UNDO.getBoundKeyLocalizedText().getString(), Keys.OPEN.getBoundKeyLocalizedText().getString()), 0xFFDDE6EE);
            y += 12;
        }
        if (st.targeting) {
            line(g, tr, x, y, L.t("hud.targeting", st.targets.size()), 0xFFFFD84D);
            y += 12;
            line(g, tr, x, y, L.t("hud.targeting_keys",
                    Keys.TARGET_ADD.getBoundKeyLocalizedText().getString(),
                    Keys.TARGET_ROTATE.getBoundKeyLocalizedText().getString(),
                    Keys.TARGET_UNDO.getBoundKeyLocalizedText().getString(),
                    Keys.OPEN.getBoundKeyLocalizedText().getString()), 0xFFDDE6EE);
            y += 12;
            if (st.hover != null) {
                line(g, tr, x, y, L.t("hud.rotation", st.hover.rot() * 90), 0xFF8B98A5);
            } else {
                line(g, tr, x, y, L.t("hud.aim_block"), 0xFFFF9F43);
            }
        }
    }

    private static void line(DrawContext g, TextRenderer tr, int x, int y, String text, int color) {
        int w = tr.getWidth(text);
        g.fill(x - 3, y - 2, x + w + 3, y + 10, 0x90000000);
        g.drawText(tr, text, x, y, color, false);
    }
}

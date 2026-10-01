package dev.baritonestudio.gui;

import dev.baritonestudio.Keys;
import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.gui.StudioScreen.Rect;
import dev.baritonestudio.gui.StudioScreen.Style;
import dev.baritonestudio.util.L;
import net.minecraft.client.option.KeyBinding;

/** Вкладка «Настройки»: клавиши и поведение. */
final class SettingsTab {
    private final StudioScreen s;

    SettingsTab(StudioScreen s) {
        this.s = s;
    }

    void render() {
        ModConfig c = ModConfig.get();
        int x = s.cx, y = s.cy, w = s.cw;
        int colGap = 14;
        boolean twoCols = w >= 360;
        int colW = twoCols ? (w - colGap) / 2 : w;

        int total = 520;
        int off = s.beginScroll("settings", x, y, w, s.ch, total);
        int cy = y - off;
        int cw = colW - 6;

        // ---- клавиши
        s.textShadow(L.t("set.keys"), x, cy, Ui.ACCENT);
        cy += 14;
        for (KeyBinding kb : Keys.ALL) {
            Rect r = new Rect(x, cy, cw, 18);
            s.text(s.trim(L.t("key." + kb.getId().replace("key.baritonestudio.", "")), cw - 74), x, cy + 5, 0xFFC4CED8);
            boolean cap = s.capturing == kb;
            String label = cap ? L.t("set.press_key") : kb.getBoundKeyLocalizedText().getString();
            s.button(x + cw - 70, cy, 70, 16, label, cap ? Style.ACCENT : Style.NORMAL, true, () -> s.capturing = kb);
            cy += 20;
        }
        s.button(x, cy + 2, cw, 16, L.t("set.reset_keys"), Style.GHOST, true, () -> {
            for (KeyBinding kb : Keys.ALL) kb.setBoundKey(kb.getDefaultKey());
            KeyBinding.updateKeysByCode();
            net.minecraft.client.MinecraftClient.getInstance().options.write();
        });
        cy += 28;
        s.wrapped(L.t("set.keys_hint"), x, cy, cw, Ui.DIM, 3);
        cy += 40;

        // ---- поведение
        int bx = twoCols ? x + colW + colGap : x;
        int by = twoCols ? y - off : cy;
        s.textShadow(L.t("set.behavior"), bx, by, Ui.ACCENT);
        by += 16;
        s.toggle(bx, by, cw, L.t("set.allow_break"), c.allowBreak, v -> c.allowBreak = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.allow_scaffold"), c.allowScaffold, v -> c.allowScaffold = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.sprint"), c.sprint, v -> c.sprint = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.collect"), c.collectDrops, v -> c.collectDrops = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.stop_full"), c.stopOnFullInventory, v -> c.stopOnFullInventory = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.hud"), c.showHud, v -> c.showHud = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.xray"), c.previewThroughWalls, v -> c.previewThroughWalls = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.labels"), c.previewLabels, v -> c.previewLabels = v);
        by += 26;

        s.slider(bx, by, cw, L.t("set.reach"), 2.0, 4.8, c.reach, false, v -> c.reach = v);
        by += 28;
        s.slider(bx, by, cw, L.t("set.rotate"), 0, 180, c.rotateSpeed, true, v -> c.rotateSpeed = v.floatValue());
        by += 28;
        s.slider(bx, by, cw, L.t("set.delay"), 1, 20, c.actionDelay, true, v -> c.actionDelay = v.intValue());
        by += 28;
        s.slider(bx, by, cw, L.t("set.radius"), 8, 96, c.searchRadius, true, v -> c.searchRadius = v.intValue());
        by += 28;
        s.slider(bx, by, cw, L.t("set.maxfall"), 1, 10, c.maxFall, true, v -> c.maxFall = v.intValue());
        by += 28;
        s.slider(bx, by, cw, L.t("set.health"), 0, 19, c.stopHealth, true, v -> c.stopHealth = v.intValue());
        by += 28;
        s.slider(bx, by, cw, L.t("set.item_wait"), 5, 300, c.itemWaitSeconds, true, v -> c.itemWaitSeconds = v.intValue());

        s.endScroll("settings");
    }
}

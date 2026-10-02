package dev.baritonestudio.gui;

import dev.baritonestudio.Keys;
import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.gui.StudioScreen.Style;
import dev.baritonestudio.util.L;
import net.minecraft.client.option.KeyBinding;

/** Вкладка «Настройки»: клавиши, поведение, Human-Mode, выход при появлении игрока. */
final class SettingsTab {
    private final StudioScreen s;
    private int contentH = 700;

    SettingsTab(StudioScreen s) {
        this.s = s;
    }

    void render() {
        ModConfig c = ModConfig.get();
        int x = s.cx, y = s.cy, w = s.cw;
        int colGap = 14;
        boolean twoCols = w >= 360;
        int colW = twoCols ? (w - colGap) / 2 : w;
        int cw = colW - 6;

        int off = s.beginScroll("settings", x, y, w, s.ch, contentH);
        int top = y - off;

        // =============== левая колонка: клавиши + выход при игроке
        int ly = top;
        s.textShadow(L.t("set.keys"), x, ly, Ui.ACCENT);
        ly += 14;
        for (KeyBinding kb : Keys.ALL) {
            s.text(s.trim(L.t("key." + kb.getId().replace("key.baritonestudio.", "")), cw - 74), x, ly + 5, 0xFFC4CED8);
            boolean cap = s.capturing == kb;
            String label = cap ? L.t("set.press_key") : kb.getBoundKeyLocalizedText().getString();
            s.button(x + cw - 70, ly, 70, 16, label, cap ? Style.ACCENT : Style.NORMAL, true, () -> s.capturing = kb);
            ly += 20;
        }
        s.button(x, ly + 2, cw, 16, L.t("set.reset_keys"), Style.GHOST, true, () -> {
            for (KeyBinding kb : Keys.ALL) kb.setBoundKey(kb.getDefaultKey());
            KeyBinding.updateKeysByCode();
            net.minecraft.client.MinecraftClient.getInstance().options.write();
        });
        ly += 26;
        int n = s.wrapped(L.t("set.keys_hint"), x, ly, cw, Ui.DIM, 4);
        ly += n * 11 + 12;

        // ---- выход, пока не увидели
        s.textShadow(L.t("set.leave"), x, ly, Ui.ACCENT);
        ly += 14;
        s.toggle(x, ly, cw, L.t("set.leave_on"), c.leaveOnPlayer, v -> c.leaveOnPlayer = v);
        ly += 18;
        n = s.wrapped(L.t("set.leave_hint"), x, ly, cw, Ui.DIM, 6);
        ly += n * 11 + 6;
        s.slider(x, ly, cw, L.t("set.leave_dist"), 16, 128, c.leaveDistance, true, v -> c.leaveDistance = v.intValue());
        ly += 28;
        s.toggle(x, ly, cw, L.t("set.leave_disconnect"), c.leaveDisconnect, v -> c.leaveDisconnect = v);
        ly += 18;
        s.toggle(x, ly, cw, L.t("set.leave_working"), c.leaveOnlyWhileWorking, v -> c.leaveOnlyWhileWorking = v);
        ly += 22;
        s.text(L.t("set.leave_friends"), x, ly, 0xFFC4CED8);
        ly += 11;
        var wl = s.field("set.friends", x, ly, cw, 16, "Steve, Alex", c.leaveWhitelist);
        c.leaveWhitelist = wl.getText();
        ly += 26;

        // =============== правая колонка: поведение + Human-Mode
        int bx = twoCols ? x + colW + colGap : x;
        int by = twoCols ? top : ly;
        s.textShadow(L.t("set.human"), bx, by, Ui.ACCENT);
        by += 14;
        s.toggle(bx, by, cw, L.t("set.human_on"), c.humanMode, v -> c.humanMode = v);
        by += 18;
        n = s.wrapped(L.t("set.human_hint"), bx, by, cw, Ui.DIM, 6);
        by += n * 11 + 6;
        s.slider(bx, by, cw, L.t("set.human_level"), 0.5, 2.0, c.humanIntensity, false, v -> c.humanIntensity = v.floatValue());
        by += 32;

        // ---- записанный почерк для деревьев
        s.toggle(bx, by, cw, L.t("set.human_profiles"), c.humanProfiles, v -> c.humanProfiles = v);
        by += 18;
        var profiles = dev.baritonestudio.human.HumanProfiles.all();
        boolean learning = dev.baritonestudio.human.HumanLearner.active();
        n = s.wrapped(L.t("set.human_profiles_hint"), bx, by, cw, Ui.DIM, 7);
        by += n * 11 + 4;
        s.text(L.t("set.human_profiles_count", profiles.size(), dev.baritonestudio.human.HumanProfiles.MAX), bx, by, profiles.isEmpty() ? Ui.WARN : Ui.OK);
        by += 14;
        if (learning) {
            s.button(bx, by, cw, 16, L.t("set.learn_stop"), StudioScreen.Style.DANGER, true, dev.baritonestudio.human.HumanLearner::stop);
        } else {
            s.button(bx, by, cw, 16, L.t("set.learn_start", dev.baritonestudio.human.HumanLearner.targetTrees), StudioScreen.Style.ACCENT, true, () -> {
                net.minecraft.client.MinecraftClient.getInstance().setScreen(null);
                dev.baritonestudio.human.HumanLearner.start();
            });
        }
        by += 20;
        s.button(bx, by, cw, 16, L.t("set.learn_reset"), StudioScreen.Style.NORMAL, !profiles.isEmpty(), () -> {
            dev.baritonestudio.human.HumanProfiles.clear();
            dev.baritonestudio.human.HumanStyle.reset();
        });
        by += 26;

        s.textShadow(L.t("set.behavior"), bx, by, Ui.ACCENT);
        by += 16;
        s.toggle(bx, by, cw, L.t("set.avoid_water"), c.avoidWater, v -> c.avoidWater = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.avoid_lava"), c.avoidLava, v -> c.avoidLava = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.allow_break"), c.allowBreak, v -> c.allowBreak = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.allow_scaffold"), c.allowScaffold, v -> c.allowScaffold = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.parkour"), c.allowParkour, v -> c.allowParkour = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.sprint"), c.sprint, v -> c.sprint = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.legit"), c.legitMine, v -> c.legitMine = v);
        by += 18;
        s.toggle(bx, by, cw, L.t("set.auto_eat"), c.autoEat, v -> c.autoEat = v);
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
        by += 22;
        s.text(L.t("set.avoid_breaking"), bx, by, 0xFFC4CED8);
        by += 11;
        var ab = s.field("set.avoid_break", bx, by, cw, 16, "chest, #minecraft:doors", c.avoidBreaking);
        c.avoidBreaking = ab.getText();
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
        by += 28;

        contentH = Math.max(ly, by) - top + 8;
        s.endScroll("settings");
    }
}

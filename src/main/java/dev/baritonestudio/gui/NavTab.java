package dev.baritonestudio.gui;

import dev.baritonestudio.Studio;
import dev.baritonestudio.gui.StudioScreen.Rect;
import dev.baritonestudio.gui.StudioScreen.Style;
import dev.baritonestudio.preset.Waypoints;
import dev.baritonestudio.util.L;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Вкладка «Навигация»: следовать, исследовать, поверхность, метки, область (очистка/заполнение). */
final class NavTab {
    private final StudioScreen s;
    private int contentH = 400;

    NavTab(StudioScreen s) {
        this.s = s;
    }

    void render() {
        Studio st = Studio.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        int x = s.cx, w = s.cw - 6;
        int off = s.beginScroll("nav", s.cx - 2, s.cy, s.cw + 4, s.ch, contentH);
        int top = s.cy - off;
        int y = top;

        // ---- быстрые действия
        s.textShadow(L.t("nav.quick"), x, y, Ui.ACCENT);
        y += 14;
        int third = (w - 8) / 3;
        s.button(x, y, third, 18, L.t("nav.surface"), Style.NORMAL, true, () -> {
            mc.setScreen(null);
            st.startSurface();
        });
        s.button(x + third + 4, y, third, 18, L.t("nav.explore"), Style.NORMAL, true, () -> {
            mc.setScreen(null);
            st.startExplore();
        });
        s.button(x + 2 * (third + 4), y, third, 18, L.t("ui.stop"), Style.DANGER, st.tasks.busy(), () -> st.stopAll(L.t("msg.stopped")));
        y += 26;

        // ---- следовать
        s.textShadow(L.t("nav.follow"), x, y, Ui.ACCENT);
        y += 14;
        int bw = Math.min(90, w / 3);
        s.field("fo.name", x, y, w - bw - 4, 16, L.t("nav.follow_hint"), "");
        s.button(x + w - bw, y, bw, 16, L.t("nav.follow_go"), Style.ACCENT, true, () -> {
            mc.setScreen(null);
            st.startFollow(s.fieldText("fo.name"), 3);
        });
        y += 26;

        // ---- метки
        s.textShadow(L.t("nav.waypoints"), x, y, Ui.ACCENT);
        y += 14;
        s.field("wp.name", x, y, w - bw - 4, 16, L.t("nav.wp_name"), "");
        s.button(x + w - bw, y, bw, 16, L.t("nav.wp_save"), Style.NORMAL, true, () -> {
            String n = s.fieldText("wp.name").trim();
            if (n.isEmpty()) n = "wp" + (Waypoints.all().size() + 1);
            Waypoints.set(n, mc.player.getBlockPos());
            s.setField("wp.name", "");
        });
        y += 22;
        if (Waypoints.all().isEmpty()) {
            int n = s.wrapped(L.t("nav.wp_empty"), x, y, w, Ui.DIM, 3);
            y += n * 11 + 4;
        }
        Waypoints.Waypoint remove = null;
        for (Waypoints.Waypoint wp : Waypoints.all()) {
            Rect r = new Rect(x, y, w, 18);
            Ui.round(s.g, r.x(), r.y(), r.w(), r.h(), s.hover(r) ? Ui.CARD_HOVER : Ui.CARD);
            s.text(s.trim(wp.name + "  " + wp.x + " " + wp.y + " " + wp.z, w - 70), r.x() + 6, r.y() + 5, Ui.TEXT);
            s.button(r.x() + r.w() - 66, r.y() + 1, 48, 16, L.t("nav.go"), Style.ACCENT, true, () -> {
                mc.setScreen(null);
                st.gotoWaypoint(wp);
            });
            s.button(r.x() + r.w() - 16, r.y() + 1, 16, 16, "✕", Style.GHOST, true, () -> Waypoints.remove(wp));
            y += 20;
        }
        y += 8;

        // ---- область
        s.textShadow(L.t("nav.area"), x, y, Ui.ACCENT);
        y += 14;
        String info = st.hasSelection()
                ? L.t("nav.area_info", Math.abs(st.selA.getX() - st.selB.getX()) + 1, Math.abs(st.selA.getY() - st.selB.getY()) + 1, Math.abs(st.selA.getZ() - st.selB.getZ()) + 1)
                : L.t("nav.area_none");
        s.text(s.trim(info, w), x, y, st.hasSelection() ? Ui.OK : Ui.DIM);
        y += 14;
        s.button(x, y, w, 16, L.t("nav.area_select"), Style.ACCENT, true, st::beginSelecting);
        y += 20;
        int half = (w - 4) / 2;
        s.button(x, y, half, 16, L.t("nav.area_clear"), Style.NORMAL, st.hasSelection(), () -> {
            mc.setScreen(null);
            st.startClearArea();
        });
        s.button(x + half + 4, y, half, 16, L.t("nav.area_reset"), Style.NORMAL, st.hasSelection(), () -> {
            st.selA = null;
            st.selB = null;
        });
        y += 22;
        s.field("fill.item", x, y, w - bw - 4, 16, L.t("nav.fill_hint"), "minecraft:cobblestone");
        s.button(x + w - bw, y, bw, 16, L.t("nav.area_fill"), Style.NORMAL, st.hasSelection(), () -> {
            Identifier id = Identifier.tryParse(s.fieldText("fill.item").trim().contains(":") ? s.fieldText("fill.item").trim() : "minecraft:" + s.fieldText("fill.item").trim());
            Item item = id == null ? null : Registries.ITEM.getOptionalValue(id).orElse(null);
            if (item == null || item == net.minecraft.item.Items.AIR) {
                st.say(net.minecraft.util.Formatting.RED, L.t("nav.fill_bad"));
                return;
            }
            mc.setScreen(null);
            st.startFillArea(item);
        });
        y += 24;

        contentH = y - top + 4;
        s.endScroll("nav");
    }
}

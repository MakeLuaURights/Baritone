package dev.baritonestudio.gui;

import dev.baritonestudio.Studio;
import dev.baritonestudio.gui.StudioScreen.Rect;
import dev.baritonestudio.gui.StudioScreen.Style;
import dev.baritonestudio.macro.Macro;
import dev.baritonestudio.macro.MacroStore;
import dev.baritonestudio.macro.Step;
import dev.baritonestudio.macro.Target;
import dev.baritonestudio.util.L;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

/** Вкладка «Запись»: запись действий, библиотека, цели повтора, предпросмотр, тексты табличек. */
final class RecorderTab {
    private enum Sub { TARGETS, STEPS, SIGNS }

    private final StudioScreen s;
    private Sub sub = Sub.TARGETS;
    private int signIndex;
    private Macro lastMacro;
    private boolean dirty;

    RecorderTab(StudioScreen s) {
        this.s = s;
    }

    /** Сохранить изменения выбранной записи (табличек, шагов). */
    void flush() {
        Studio st = Studio.get();
        if (dirty && st.macro != null) {
            MacroStore.save(st.macro);
        }
        dirty = false;
    }

    void render() {
        Studio st = Studio.get();
        int x0 = s.cx, y0 = s.cy;

        // ---- верхняя панель записи
        Ui.round(s.g, x0, y0, s.cw, 30, 0xFF181E25);
        if (st.recorder.active()) {
            int pulse = (int) (System.currentTimeMillis() / 450 % 2);
            s.g.fill(x0 + 10, y0 + 11, x0 + 18, y0 + 19, pulse == 0 ? Ui.DANGER : 0xFF7A2A2A);
            s.text(s.trim(L.t("rec.recording", st.recorder.actionCount()), s.cw / 2 - 30), x0 + 24, y0 + 11, Ui.TEXT);
            int nw = Math.min(130, s.cw / 4);
            s.field("rec.name", x0 + s.cw - nw - 150, y0 + 7, nw, 16, L.t("rec.name_hint"), st.nextMacroName());
            s.button(x0 + s.cw - 144, y0 + 5, 138, 20, "■ " + L.t("rec.stop"), Style.DANGER, true, () -> {
                String name = s.fieldText("rec.name").trim();
                if (name.isEmpty()) name = st.nextMacroName();
                Studio.get().stopRecording(name);
                s.setField("rec.name", "");
            });
        } else {
            s.text(s.trim(L.t("rec.hint"), s.cw - 160), x0 + 8, y0 + 11, Ui.DIM);
            s.button(x0 + s.cw - 144, y0 + 5, 138, 20, "● " + L.t("rec.start"), Style.DANGER, true, () -> {
                MinecraftClient.getInstance().setScreen(null);
                Studio.get().startRecording();
            });
        }

        int top = y0 + 36;
        int bodyH = s.ch - 36;
        int listW = Math.min(150, s.cw / 3);

        // ---- библиотека
        List<Macro> lib = st.library();
        if (st.macro != lastMacro) {
            flush();
            lastMacro = st.macro;
            signIndex = 0;
            for (int i = 0; i < 4; i++) s.setField("sg." + i, "");
            s.setField("rn.name", st.macro == null ? "" : st.macro.name);
            s.setField("sg.start", st.macro == null ? "1" : Integer.toString(st.macro.counterStart));
            s.setField("sg.step", st.macro == null ? "1" : Integer.toString(st.macro.counterStep));
            signFieldsFor = null;
        }
        if (lib.isEmpty()) {
            s.wrapped(L.t("rec.empty"), x0 + 4, top + 4, listW - 8, Ui.DIM, 6);
        }
        int rowH = 30;
        int off = s.beginScroll("rec.list", x0, top, listW, bodyH, lib.size() * (rowH + 3));
        int y = top - off;
        for (Macro m : lib) {
            Rect r = new Rect(x0, y, listW - 5, rowH);
            boolean sel = st.macro == m;
            s.card(r.x(), r.y(), r.w(), r.h(), sel, s.hover(r));
            s.text(s.trim(m.name, r.w() - 12), r.x() + 8, r.y() + 6, Ui.TEXT);
            s.text(L.t("rec.actions", m.actionCount()), r.x() + 8, r.y() + 17, Ui.DIM);
            s.region(r, px -> {
                flush();
                st.select(m);
            });
            y += rowH + 3;
        }
        s.endScroll("rec.list");

        // ---- правая часть
        int rx = x0 + listW + 8, rw = s.cw - listW - 8;
        Ui.round(s.g, rx, top, rw, bodyH, 0xFF181E25);
        if (st.macro == null) {
            s.wrapped(L.t("rec.select_hint"), rx + 10, top + 10, rw - 20, Ui.DIM, 4);
            return;
        }
        Macro m = st.macro;

        // под-вкладки
        int tx = rx + 8;
        Sub[] subs = m.hasSigns() ? Sub.values() : new Sub[]{Sub.TARGETS, Sub.STEPS};
        if (sub == Sub.SIGNS && !m.hasSigns()) sub = Sub.TARGETS;
        for (Sub t : subs) {
            String label = switch (t) {
                case TARGETS -> L.t("rec.sub.targets");
                case STEPS -> L.t("rec.sub.steps");
                case SIGNS -> L.t("rec.sub.signs");
            };
            int w = s.font().getWidth(label) + 14;
            boolean sel = sub == t;
            Rect r = new Rect(tx, top + 5, w, 16);
            Ui.round(s.g, r.x(), r.y(), r.w(), r.h(), sel ? Ui.withAlpha(Ui.ACCENT, 60) : (s.hover(r) ? Ui.CARD : 0));
            s.text(label, r.x() + 7, r.y() + 4, sel ? Ui.TEXT : Ui.DIM);
            if (sel) s.g.fill(r.x() + 2, r.y() + r.h(), r.x() + r.w() - 2, r.y() + r.h() + 1, Ui.ACCENT);
            s.region(r, px -> {
                flush();
                sub = t;
            });
            tx += w + 4;
        }
        // название + удалить
        String title = s.trim(m.name, rw - (tx - rx) - 16);
        s.text(title, rx + rw - 8 - s.font().getWidth(title), top + 9, Ui.DIM);

        int cx = rx + 8, cy = top + 28, cw = rw - 16, ch = bodyH - 34;
        switch (sub) {
            case TARGETS -> renderTargets(st, m, cx, cy, cw, ch);
            case STEPS -> renderSteps(st, m, cx, cy, cw, ch);
            case SIGNS -> renderSigns(st, m, cx, cy, cw, ch);
        }
    }

    // ------------------------------------------------------------ цели

    private void renderTargets(Studio st, Macro m, int x, int y, int w, int h) {
        int moves = m.count(Step.Type.MOVE), br = m.count(Step.Type.BREAK), us = m.count(Step.Type.USE), sg = m.count(Step.Type.SIGN);
        s.text(s.trim(L.t("rec.stats", m.steps.size(), br, us, sg), w), x, y, 0xFFC4CED8);
        y += 13;

        // нужные предметы
        Map<Item, Integer> need = st.requiredItems();
        if (!need.isEmpty() && MinecraftClient.getInstance().player != null) {
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                int have = st.tasks.actor().countItem(is -> is.isOf(e.getKey()));
                if (have < e.getValue()) ok = false;
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey().getName().getString()).append(" ").append(have).append("/").append(e.getValue());
            }
            int n = s.wrapped(L.t("rec.need", sb.toString()), x, y, w, ok ? Ui.OK : Ui.WARN, 2);
            y += n * 11 + 3;
        }

        // кнопки
        int bw = (w - 8) / 3;
        s.button(x, y, bw, 18, "⌖ " + L.t("rec.pick_places"), Style.ACCENT, true, st::beginTargeting);
        s.button(x + bw + 4, y, bw, 18, L.t(st.previewEnabled ? "rec.preview_on" : "rec.preview_off"), Style.NORMAL, true,
                () -> st.previewEnabled = !st.previewEnabled);
        s.button(x + 2 * (bw + 4), y, bw, 18, L.t("rec.clear_targets"), Style.NORMAL, !st.targets.isEmpty(), st.targets::clear);
        y += 24;

        // серия
        int fw = 34;
        s.text(L.t("rec.series"), x, y + 5, 0xFFC4CED8);
        int sx = x + s.font().getWidth(L.t("rec.series")) + 6;
        s.field("sr.n", sx, y, fw, 16, "N", "5");
        s.field("sr.dx", sx + fw + 4, y, fw, 16, "dX", "0");
        s.field("sr.dy", sx + 2 * (fw + 4), y, fw, 16, "dY", "0");
        s.field("sr.dz", sx + 3 * (fw + 4), y, fw, 16, "dZ", "2");
        int addX = sx + 4 * (fw + 4);
        s.button(addX, y, Math.max(40, x + w - addX), 16, L.t("rec.series_add"), Style.NORMAL, !st.targets.isEmpty(), () -> {
            st.addSeries(s.intField("sr.n", 1), s.intField("sr.dx", 0), s.intField("sr.dy", 0), s.intField("sr.dz", 0));
        });
        y += 22;

        // список целей
        int bottomH = 44;
        int availH = (s.cy + s.ch) - y - bottomH - 6;
        availH = Math.max(24, availH);
        List<Target> ts = st.targets;
        if (ts.isEmpty()) {
            s.wrapped(L.t("rec.no_targets"), x, y + 2, w, Ui.DIM, 3);
        }
        int rowH = 16;
        int off = s.beginScroll("rec.targets", x, y, w, availH, ts.size() * (rowH + 2));
        int ry = y - off;
        for (int i = 0; i < ts.size(); i++) {
            Target t = ts.get(i);
            Rect r = new Rect(x, ry, w - 5, rowH);
            Ui.round(s.g, r.x(), r.y(), r.w(), r.h(), s.hover(r) ? Ui.CARD_HOVER : Ui.CARD);
            BlockPos p = t.ref();
            s.text("#" + (i + 1) + "  " + p.getX() + ", " + p.getY() + ", " + p.getZ() + "   ↻ " + (t.rot() * 90) + "°", r.x() + 6, r.y() + 4, Ui.TEXT);
            int idx = i;
            s.button(r.x() + r.w() - 16, r.y(), 16, rowH, "✕", Style.GHOST, true, () -> ts.remove(idx));
            ry += rowH + 2;
        }
        s.endScroll("rec.targets");

        // низ: запуск + управление записью
        int by = s.cy + s.ch - 44;
        boolean busy = st.tasks.busy();
        if (busy) {
            s.button(x, by, w, 20, L.t("ui.stop"), Style.DANGER, true, () -> st.stopAll(L.t("msg.stopped")));
        } else {
            s.button(x, by, w, 20, "▶ " + L.t("rec.run", ts.size()), Style.ACCENT, !ts.isEmpty(), () -> {
                MinecraftClient.getInstance().setScreen(null);
                st.runMacro();
            });
        }
        int nameW = w - 154;
        s.field("rn.name", x, by + 24, nameW, 16, L.t("rec.name_hint"), m.name);
        s.button(x + nameW + 4, by + 24, 74, 16, L.t("ui.rename"), Style.NORMAL, true, () -> {
            String nn = s.fieldText("rn.name").trim();
            if (!nn.isEmpty() && !nn.equals(m.name)) {
                MacroStore.rename(m, nn);
                st.reloadLibrary();
                for (Macro mm : st.library()) if (mm.name.equals(nn)) { st.macro = mm; lastMacro = mm; }
            }
        });
        s.button(x + nameW + 82, by + 24, 72, 16, L.t("ui.delete"), Style.DANGER, true, () -> {
            MacroStore.delete(m);
            st.macro = null;
            lastMacro = null;
            st.targets.clear();
            st.reloadLibrary();
        });
    }

    // ------------------------------------------------------------ шаги

    private void renderSteps(Studio st, Macro m, int x, int y, int w, int h) {
        s.text(s.trim(L.t("rec.steps_hint"), w), x, y, Ui.DIM);
        y += 14;
        int availH = (s.cy + s.ch) - y - 4;
        int rowH = 15;
        int off = s.beginScroll("rec.steps", x, y, w, availH, m.steps.size() * (rowH + 1));
        int ry = y - off;
        int removeIdx = -1;
        for (int i = 0; i < m.steps.size(); i++) {
            Step stp = m.steps.get(i);
            Rect r = new Rect(x, ry, w - 6, rowH);
            Ui.round(s.g, r.x(), r.y(), r.w(), r.h(), s.hover(r) ? Ui.CARD_HOVER : Ui.CARD);
            int col = switch (stp.type) {
                case MOVE -> 0xFF4DE3E3;
                case BREAK -> 0xFFFF5555;
                case USE -> stp.placed ? 0xFF55E07A : 0xFF5AA9FF;
                case SIGN -> 0xFFFFD84D;
            };
            s.g.fill(r.x() + 2, r.y() + 3, r.x() + 4, r.y() + r.h() - 3, col);
            s.text(s.trim((i + 1) + ". " + describe(stp), r.w() - 26), r.x() + 8, r.y() + 4, Ui.TEXT);
            int idx = i;
            s.button(r.x() + r.w() - 15, r.y(), 15, rowH, "✕", Style.GHOST, true, () -> {
                m.steps.remove(idx);
                dirty = true;
            });
            ry += rowH + 1;
        }
        s.endScroll("rec.steps");
    }

    static String describe(Step stp) {
        return switch (stp.type) {
            case MOVE -> L.t("step.move", stp.x, stp.y, stp.z);
            case BREAK -> L.t("step.break", shortId(stp.block), stp.x, stp.y, stp.z);
            case USE -> stp.placed
                    ? L.t("step.place", shortId(stp.block.isEmpty() ? stp.item : stp.block), stp.px, stp.py, stp.pz)
                    : L.t("step.use", shortId(stp.item), stp.x, stp.y, stp.z);
            case SIGN -> L.t("step.sign", signPreview(stp));
        };
    }

    private static String signPreview(Step stp) {
        StringBuilder sb = new StringBuilder();
        for (String l : stp.lines) {
            if (l != null && !l.isEmpty()) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(l);
            }
        }
        return sb.toString();
    }

    private static String shortId(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    // ------------------------------------------------------------ таблички

    private Step signFieldsFor;

    private void renderSigns(Studio st, Macro m, int x, int y, int w, int h) {
        List<Step> signs = m.steps.stream().filter(t -> t.type == Step.Type.SIGN).toList();
        if (signs.isEmpty()) return;
        signIndex = Math.max(0, Math.min(signs.size() - 1, signIndex));
        Step sign = signs.get(signIndex);

        // выбор таблички
        s.text(L.t("rec.sign_of", signIndex + 1, signs.size()), x, y + 5, Ui.TEXT);
        s.button(x + w - 40, y, 18, 16, "<", Style.NORMAL, signIndex > 0, () -> signIndex--);
        s.button(x + w - 20, y, 18, 16, ">", Style.NORMAL, signIndex < signs.size() - 1, () -> signIndex++);
        y += 24;

        if (signFieldsFor != sign) {
            signFieldsFor = sign;
            for (int i = 0; i < 4; i++) s.setField("sg." + i, sign.lines[i] == null ? "" : sign.lines[i]);
        }
        for (int i = 0; i < 4; i++) {
            s.text(L.t("rec.sign_line", i + 1), x, y + 5, 0xFFC4CED8);
            var f = s.field("sg." + i, x + 54, y, w - 54, 16, "", sign.lines[i]);
            String cur = f.getText();
            if (!cur.equals(sign.lines[i])) {
                sign.lines[i] = cur;
                dirty = true;
            }
            y += 20;
        }
        y += 4;
        s.wrapped(L.t("rec.sign_hint"), x, y, w, Ui.DIM, 3);
        y += 36;

        s.text(L.t("rec.counter_start"), x, y + 5, 0xFFC4CED8);
        s.field("sg.start", x + w - 130, y, 56, 16, "1", Integer.toString(m.counterStart));
        s.field("sg.step", x + w - 66, y, 56, 16, "1", Integer.toString(m.counterStep));
        int ns = s.intField("sg.start", m.counterStart), nt = s.intField("sg.step", m.counterStep);
        if (ns != m.counterStart || nt != m.counterStep) {
            m.counterStart = ns;
            m.counterStep = nt;
            dirty = true;
        }
        y += 24;
        // пример результата
        StringBuilder ex = new StringBuilder();
        for (int i = 0; i < Math.min(3, Math.max(1, st.targets.size())); i++) {
            int n = m.counterStart + i * m.counterStep;
            String line = dev.baritonestudio.macro.Transform.fill(firstLine(sign), n);
            if (ex.length() > 0) ex.append("  →  ");
            ex.append(line);
        }
        s.text(s.trim(L.t("rec.sign_example", ex.toString()), w), x, y, Ui.ACCENT);
    }

    private static String firstLine(Step sign) {
        for (String l : sign.lines) if (l != null && !l.isEmpty()) return l;
        return "";
    }
}

package dev.baritonestudio.gui;

import dev.baritonestudio.Studio;
import dev.baritonestudio.gui.StudioScreen.Rect;
import dev.baritonestudio.gui.StudioScreen.Style;
import dev.baritonestudio.preset.Preset;
import dev.baritonestudio.preset.PresetStore;
import dev.baritonestudio.task.BlockMatcher;
import dev.baritonestudio.util.L;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;

/** Вкладка «Пресеты»: готовые работы и свои настройки. */
final class PresetsTab {
    private final StudioScreen s;
    private Preset selected;
    private Preset editing;
    private boolean editingIsNew;
    private String lastSelectedId = "";

    PresetsTab(StudioScreen s) {
        this.s = s;
    }

    void render() {
        List<Preset> all = PresetStore.all();
        if (selected == null && !all.isEmpty()) selected = all.get(0);

        int listW = Math.min(190, s.cw / 2 - 4);
        int x0 = s.cx, y0 = s.cy;

        // ---- список
        s.button(x0, y0, listW, 18, "+ " + L.t("preset.new"), Style.ACCENT, true, this::startNew);
        int ly = y0 + 22, lh = s.ch - 22;
        int rowH = 32;
        int off = s.beginScroll("presets.list", x0, ly, listW, lh, all.size() * (rowH + 3));
        int y = ly - off;
        for (Preset p : all) {
            Rect r = new Rect(x0, y, listW - 5, rowH);
            boolean sel = selected != null && selected.id.equals(p.id);
            boolean hov = s.hover(r);
            s.card(r.x(), r.y(), r.w(), r.h(), sel, hov);
            s.drawItem(p.icon, r.x() + 8, r.y() + 8);
            s.text(s.trim(p.displayName(), r.w() - 36 - (p.builtin ? 0 : 14)), r.x() + 30, r.y() + 6, Ui.TEXT);
            s.text(s.trim(summary(p), r.w() - 36), r.x() + 30, r.y() + 18, Ui.DIM);
            if (!p.builtin) s.text("★", r.x() + r.w() - 12, r.y() + 6, Ui.WARN);
            s.region(r, px -> {
                selected = p;
                editing = null;
            });
            y += rowH + 3;
        }
        s.endScroll("presets.list");

        // ---- правая панель
        int rx = x0 + listW + 8, rw = s.cw - listW - 8;
        Ui.round(s.g, rx, y0, rw, s.ch, 0xFF181E25);
        if (editing != null) {
            renderEditor(rx + 8, y0 + 8, rw - 16);
        } else if (selected != null) {
            renderDetails(rx + 8, y0 + 8, rw - 16);
        }
    }

    private String summary(Preset p) {
        return switch (p.type) {
            case MINE -> L.t("preset.type.mine");
            case TUNNEL -> L.t("preset.type.tunnel");
            case LEVEL -> L.t("preset.type.level");
            case COLLECT -> L.t("preset.type.collect");
        };
    }

    // ------------------------------------------------------------ детали

    private int detailH = 200;
    private int editorH = 200;

    private void renderDetails(int x, int y, int w) {
        Preset p = selected;
        if (!p.id.equals(lastSelectedId)) {
            lastSelectedId = p.id;
            s.setField("pr.count", defaultParam(p));
            s.setField("pr.radius", p.radius > 0 ? Integer.toString(p.radius) : "");
            s.resetScroll("presets.detail");
        }
        int btnArea = 52;
        int availH = s.cy + s.ch - 8 - y - btnArea;
        int off = s.beginScroll("presets.detail", x - 2, y, w + 4, availH, detailH);
        int top = y - off;
        int cw = w - 6;
        int yy = top;

        s.drawItem(p.icon, x, yy);
        s.textShadow(s.trim(p.displayName(), cw - 24), x + 22, yy + 4, Ui.TEXT);
        yy += 24;
        int lines = s.wrapped(p.displayDescription(), x, yy, cw, Ui.DIM, 4);
        yy += Math.max(1, lines) * 11 + 6;

        if (p.type == Preset.Type.MINE) {
            s.text(L.t("preset.blocks"), x, yy, Ui.ACCENT);
            yy += 11;
            int n = s.wrapped(String.join(", ", p.blocks), x, yy, cw, 0xFFC4CED8, 3);
            yy += n * 11 + 6;
        }

        int fw = 56;
        String countLabel = switch (p.type) {
            case MINE -> L.t("preset.param.count");
            case TUNNEL -> L.t("preset.param.length");
            case LEVEL -> L.t("preset.param.level");
            case COLLECT -> L.t("preset.param.radius");
        };
        if (p.type == Preset.Type.COLLECT) {
            s.text(s.trim(countLabel, cw - fw - 6), x, yy + 5, 0xFFC4CED8);
            s.field("pr.radius", x + cw - fw, yy, fw, 16, "16", "");
            yy += 22;
        } else {
            s.text(s.trim(countLabel, cw - fw - 6), x, yy + 5, 0xFFC4CED8);
            s.field("pr.count", x + cw - fw, yy, fw, 16, "0", defaultParam(p));
            yy += 22;
            if (p.type == Preset.Type.MINE) {
                s.text(s.trim(L.t("preset.param.radius"), cw - fw - 6), x, yy + 5, 0xFFC4CED8);
                s.field("pr.radius", x + cw - fw, yy, fw, 16, L.t("preset.param.auto"), "");
                yy += 22;
            }
        }
        if (p.type == Preset.Type.LEVEL) {
            var pl = MinecraftClient.getInstance().player;
            s.text(L.t("preset.level_hint", pl != null ? pl.getBlockY() : 0), x, yy, Ui.DIM);
            yy += 13;
        }
        if (p.type == Preset.Type.MINE && p.replant) {
            s.wrapped(L.t("preset.replant_note"), x, yy, cw, Ui.DIM, 2);
            yy += 24;
        }
        detailH = yy - top + 4;
        s.endScroll("presets.detail");

        // кнопки внизу, всегда на виду
        int by = s.cy + s.ch - 28;
        boolean busy = Studio.get().tasks.busy();
        if (busy) {
            s.button(x, by, w, 20, L.t("ui.stop"), Style.DANGER, true, () -> Studio.get().stopAll(L.t("msg.stopped")));
        } else {
            s.button(x, by, w, 20, "▶ " + L.t("ui.run"), Style.ACCENT, true, () -> run(p));
        }
        int by2 = by - 22;
        if (p.builtin) {
            s.button(x, by2, w, 18, L.t("preset.copy_edit"), Style.NORMAL, true, () -> startCopy(p));
        } else {
            int half = (w - 4) / 2;
            s.button(x, by2, half, 18, L.t("ui.edit"), Style.NORMAL, true, () -> startEdit(p));
            s.button(x + half + 4, by2, half, 18, L.t("ui.delete"), Style.DANGER, true, () -> {
                PresetStore.delete(p);
                selected = null;
            });
        }
    }

    private String defaultParam(Preset p) {
        if (p.type == Preset.Type.LEVEL) {
            return Integer.toString(p.count);
        }
        return Integer.toString(p.count);
    }

    private void run(Preset p) {
        Integer count = null, radius = null;
        if (p.type != Preset.Type.COLLECT) count = s.intField("pr.count", p.count);
        int r = s.intField("pr.radius", 0);
        if (r > 0) radius = r;
        MinecraftClient.getInstance().setScreen(null);
        Studio.get().startPreset(p, count, radius);
    }

    // ------------------------------------------------------------ редактор

    private void startNew() {
        Preset p = new Preset();
        p.name = L.t("preset.new_name");
        p.icon = "minecraft:stone";
        p.type = Preset.Type.MINE;
        beginEdit(p, true);
    }

    private void startCopy(Preset src) {
        Preset p = src.copy();
        p.id = "";
        p.name = src.displayName() + " *";
        p.description = src.displayDescription();
        beginEdit(p, true);
    }

    private void startEdit(Preset src) {
        beginEdit(src.copy(), false);
    }

    private void beginEdit(Preset p, boolean isNew) {
        editing = p;
        editingIsNew = isNew;
        s.setField("pe.name", p.name);
        s.setField("pe.desc", p.description == null ? "" : p.description);
        s.setField("pe.icon", p.icon);
        s.setField("pe.blocks", String.join(", ", p.blocks));
        s.setField("pe.count", Integer.toString(p.count));
        s.setField("pe.radius", Integer.toString(p.radius));
    }

    private void renderEditor(int x, int y, int w) {
        Preset p = editing;
        s.textShadow(editingIsNew ? L.t("preset.editor.new") : L.t("preset.editor.edit"), x, y, Ui.TEXT);
        int availH = s.cy + s.ch - 8 - (y + 14) - 30;
        int off = s.beginScroll("presets.editor", x - 2, y + 14, w + 4, availH, editorH);
        int top = y + 16 - off;
        int yy = top;
        int cw = w - 6;
        int lw = 70;
        int fw = cw - lw;

        s.text(L.t("preset.editor.name"), x, yy + 5, 0xFFC4CED8);
        s.field("pe.name", x + lw, yy, fw, 16, L.t("preset.editor.name"), p.name);
        yy += 21;

        s.text(L.t("preset.editor.icon"), x, yy + 5, 0xFFC4CED8);
        s.field("pe.icon", x + lw, yy, fw - 22, 16, "minecraft:stone", p.icon);
        s.drawItem(s.fieldText("pe.icon").trim(), x + cw - 18, yy);
        yy += 21;

        s.text(L.t("preset.editor.type"), x, yy + 5, 0xFFC4CED8);
        s.button(x + lw, yy, fw, 16, summary(p), Style.NORMAL, true, () -> {
            Preset.Type[] t = Preset.Type.values();
            p.type = t[(p.type.ordinal() + 1) % t.length];
        });
        yy += 21;

        if (p.type == Preset.Type.MINE) {
            s.text(L.t("preset.editor.blocks"), x, yy + 5, 0xFFC4CED8);
            s.field("pe.blocks", x + lw, yy, fw, 16, "stone, #minecraft:logs, wheat[age=7]", String.join(", ", p.blocks));
            yy += 19;
            BlockMatcher m = BlockMatcher.parse(parseBlocks());
            if (!m.invalid.isEmpty()) {
                s.wrapped(L.t("preset.editor.unknown", String.join(", ", m.invalid)), x, yy, cw, Ui.DANGER, 2);
            } else {
                s.wrapped(L.t("preset.editor.blocks_hint"), x, yy, cw, Ui.DIM, 2);
            }
            yy += 24;
        }

        String cl = switch (p.type) {
            case MINE -> L.t("preset.param.count");
            case TUNNEL -> L.t("preset.param.length");
            case LEVEL -> L.t("preset.param.level");
            case COLLECT -> L.t("preset.param.radius");
        };
        if (p.type != Preset.Type.COLLECT) {
            s.text(s.trim(cl, cw - 62), x, yy + 5, 0xFFC4CED8);
            s.field("pe.count", x + cw - 56, yy, 56, 16, "0", Integer.toString(p.count));
            yy += 21;
        }
        if (p.type == Preset.Type.MINE || p.type == Preset.Type.COLLECT) {
            s.text(s.trim(L.t("preset.param.radius"), cw - 62), x, yy + 5, 0xFFC4CED8);
            s.field("pe.radius", x + cw - 56, yy, 56, 16, L.t("preset.param.auto"), Integer.toString(p.radius));
            yy += 21;
        }
        if (p.type == Preset.Type.MINE) {
            s.toggle(x, yy, cw, L.t("preset.editor.replant"), p.replant, v -> p.replant = v);
            yy += 20;
        }
        editorH = yy - top + 4;
        s.endScroll("presets.editor");

        int by = s.cy + s.ch - 28;
        int half = (w - 4) / 2;
        s.button(x, by, half, 20, L.t("ui.save"), Style.ACCENT, true, this::save);
        s.button(x + half + 4, by, half, 20, L.t("ui.cancel"), Style.NORMAL, true, () -> editing = null);
    }

    private List<String> parseBlocks() {
        List<String> out = new ArrayList<>();
        String raw = s.fieldText("pe.blocks");
        // запятые внутри [] не разделяют элементы
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char c : raw.toCharArray()) {
            if (c == '[') depth++;
            if (c == ']') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    private void save() {
        Preset p = editing;
        p.name = s.fieldText("pe.name").trim();
        if (p.name.isEmpty()) p.name = L.t("preset.new_name");
        p.description = s.fieldText("pe.desc");
        p.icon = s.fieldText("pe.icon").trim();
        if (p.icon.isEmpty()) p.icon = "minecraft:stone";
        if (p.type == Preset.Type.MINE) p.blocks = parseBlocks();
        p.count = s.intField("pe.count", 0);
        p.radius = Math.max(0, s.intField("pe.radius", 0));
        PresetStore.upsert(p);
        selected = p;
        lastSelectedId = "";
        editing = null;
    }
}

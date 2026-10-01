package dev.baritonestudio.gui;

import net.minecraft.client.gui.DrawContext;

/** Цвета и простые примитивы рисования для интерфейса мода. */
public final class Ui {
    public static final int BG = 0xF0101418;
    public static final int PANEL = 0xFF151A20;
    public static final int SIDEBAR = 0xFF11161B;
    public static final int CARD = 0xFF1C232B;
    public static final int CARD_HOVER = 0xFF273240;
    public static final int BORDER = 0xFF2B3540;
    public static final int ACCENT = 0xFF2DD4BF;
    public static final int ACCENT_DARK = 0xFF157A6E;
    public static final int ACCENT_HOVER = 0xFF5FE8D5;
    public static final int TEXT = 0xFFE6EDF3;
    public static final int DIM = 0xFF8B98A5;
    public static final int DANGER = 0xFFEF4444;
    public static final int DANGER_DARK = 0xFF8F2A2A;
    public static final int OK = 0xFF22C55E;
    public static final int WARN = 0xFFF59E0B;

    private Ui() {}

    /** Прямоугольник со срезанными углами. */
    public static void round(DrawContext g, int x, int y, int w, int h, int color) {
        if (w < 4 || h < 4) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void border(DrawContext g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void progress(DrawContext g, int x, int y, int w, int h, double v) {
        round(g, x, y, w, h, 0xFF2A323B);
        int fw = (int) Math.round(w * Math.max(0, Math.min(1, v)));
        if (fw > 0) round(g, x, y, Math.max(fw, 2), h, ACCENT);
    }

    public static int withAlpha(int color, int a) {
        return (a << 24) | (color & 0xFFFFFF);
    }
}

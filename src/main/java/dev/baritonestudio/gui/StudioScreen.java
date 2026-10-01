package dev.baritonestudio.gui;

import dev.baritonestudio.Keys;
import dev.baritonestudio.Studio;
import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.util.L;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Главное окно мода. Интерфейс «непосредственного режима»: каждый кадр вкладки рисуют свои элементы
 * через вспомогательные методы (button/field/toggle/slider/scroll), которые заодно регистрируют зоны кликов.
 */
public class StudioScreen extends Screen {
    public enum TabId { PRESETS, RECORDER, NAV, STATUS, SETTINGS }

    static TabId currentTab = TabId.PRESETS;

    public static void openTab(TabId t) {
        currentTab = t;
    }

    /** Открыть редактор нового пресета (для тестов и быстрых ссылок). */
    public void openPresetEditor() {
        currentTab = TabId.PRESETS;
        presetsTab.openNewEditor();
    }

    /** Открыть под-вкладку «Записи»: 0 — повтор, 1 — шаги, 2 — таблички. */
    public void openRecorderSub(int i) {
        currentTab = TabId.RECORDER;
        recorderTab.showSub(i);
    }

    public record Rect(int x, int y, int w, int h) {
        public boolean has(double px, double py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    private record Region(Rect r, Rect clip, DoubleConsumer onPress, boolean drag) {}

    private record ScrollArea(String id, Rect r, int contentH) {}

    // ---- состояние кадра
    DrawContext g;
    int mx, my;
    float dt;
    private final List<Region> regions = new ArrayList<>();
    private final List<ScrollArea> scrollAreas = new ArrayList<>();
    private Rect clip;
    private String tip;
    private Region dragging;

    // ---- постоянное состояние
    private final Map<String, TextFieldWidget> fields = new HashMap<>();
    private final Set<String> shown = new HashSet<>();
    private TextFieldWidget focused;
    private final Map<String, Integer> scroll = new HashMap<>();
    KeyBinding capturing;

    // ---- раскладка
    int px, py, pw, ph;
    /** Область содержимого вкладки. */
    int cx, cy, cw, ch;

    final PresetsTab presetsTab = new PresetsTab(this);
    final RecorderTab recorderTab = new RecorderTab(this);
    final NavTab navTab = new NavTab(this);
    final StatusTab statusTab = new StatusTab(this);
    final SettingsTab settingsTab = new SettingsTab(this);

    public StudioScreen() {
        super(Text.literal("Baritone Studio"));
    }

    // ================================================================ Screen

    @Override
    protected void init() {
        // поля пересоздаются под новый размер экрана
        fields.clear();
        focused = null;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        ModConfig.save();
        recorderTab.flush();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        // свой лёгкий фон вместо размытия
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.g = context;
        this.mx = mouseX;
        this.my = mouseY;
        this.dt = delta;
        regions.clear();
        scrollAreas.clear();
        shown.clear();
        clip = null;
        tip = null;

        context.fill(0, 0, width, height, 0x88000000);

        pw = Math.min(width - 16, 580);
        ph = Math.min(height - 16, 350);
        px = (width - pw) / 2;
        py = (height - ph) / 2;

        Ui.round(context, px - 1, py - 1, pw + 2, ph + 2, Ui.BORDER);
        Ui.round(context, px, py, pw, ph, Ui.PANEL);
        header(context);
        sidebar(context);

        cx = px + 104;
        cy = py + 34;
        cw = pw - 104 - 8;
        ch = ph - 34 - 8;

        switch (currentTab) {
            case PRESETS -> presetsTab.render();
            case RECORDER -> recorderTab.render();
            case NAV -> navTab.render();
            case STATUS -> statusTab.render();
            case SETTINGS -> settingsTab.render();
        }

        // поля, которые в этом кадре не показаны, теряют фокус
        if (focused != null && !shown.contains(idOf(focused))) {
            focused.setFocused(false);
            focused = null;
        }

        if (tip != null) context.drawTooltip(textRenderer, List.of(Text.literal(tip)), java.util.Optional.empty(), mouseX, mouseY);
    }

    private String idOf(TextFieldWidget f) {
        for (Map.Entry<String, TextFieldWidget> e : fields.entrySet()) if (e.getValue() == f) return e.getKey();
        return "";
    }

    private void header(DrawContext c) {
        c.fill(px, py, px + pw, py + 28, Ui.SIDEBAR);
        c.fill(px, py + 27, px + pw, py + 28, Ui.BORDER);
        c.fill(px + 8, py + 8, px + 12, py + 20, Ui.ACCENT);
        c.drawText(textRenderer, "Baritone Studio", px + 18, py + 7, Ui.TEXT, true);
        c.drawText(textRenderer, L.t("ui.subtitle"), px + 18 + textRenderer.getWidth("Baritone Studio") + 8, py + 7, Ui.DIM, false);

        Studio st = Studio.get();
        String pill;
        int col;
        if (st.recorder.active()) {
            pill = L.t("ui.pill.recording");
            col = Ui.DANGER;
        } else if (st.tasks.busy()) {
            pill = L.t("ui.pill.working");
            col = Ui.ACCENT;
        } else {
            pill = L.t("ui.pill.idle");
            col = Ui.DIM;
        }
        int w = textRenderer.getWidth(pill) + 14;
        int x = px + pw - w - 8;
        Ui.round(c, x, py + 6, w, 16, Ui.withAlpha(col, 60));
        c.fill(x + 5, py + 12, x + 8, py + 15, col);
        c.drawText(textRenderer, pill, x + 11, py + 10, col, false);
    }

    private void sidebar(DrawContext c) {
        c.fill(px, py + 28, px + 100, py + ph, Ui.SIDEBAR);
        c.fill(px + 100, py + 28, px + 101, py + ph, Ui.BORDER);
        TabId[] tabs = TabId.values();
        String[] icons = {"minecraft:iron_pickaxe", "minecraft:writable_book", "minecraft:filled_map", "minecraft:compass", "minecraft:comparator"};
        String[] names = {L.t("tab.presets"), L.t("tab.recorder"), L.t("tab.nav"), L.t("tab.status"), L.t("tab.settings")};
        for (int i = 0; i < tabs.length; i++) {
            int y = py + 34 + i * 25;
            TabId t = tabs[i];
            boolean sel = currentTab == t;
            Rect r = new Rect(px + 6, y, 90, 22);
            boolean hov = hover(r);
            if (sel) {
                Ui.round(c, r.x, r.y, r.w, r.h, Ui.withAlpha(Ui.ACCENT, 50));
                c.fill(r.x, r.y + 3, r.x + 2, r.y + r.h - 3, Ui.ACCENT);
            } else if (hov) {
                Ui.round(c, r.x, r.y, r.w, r.h, Ui.CARD);
            }
            drawItem(icons[i], r.x + 6, r.y + 3);
            c.drawText(textRenderer, names[i], r.x + 26, r.y + 7, sel ? Ui.TEXT : Ui.DIM, false);
            region(r, x -> {
                currentTab = t;
                unfocus();
            });
        }
        // подсказка внизу
        String hint = Keys.OPEN.getBoundKeyLocalizedText().getString() + " — " + L.t("ui.close");
        c.drawText(textRenderer, hint, px + 8, py + ph - 14, Ui.DIM, false);
    }

    // ================================================================ события

    private void unfocus() {
        if (focused != null) focused.setFocused(false);
        focused = null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = click.x(), y = click.y();
        if (capturing != null) {
            capturing = null;
            return true;
        }
        // поля ввода
        for (String id : shown) {
            TextFieldWidget f = fields.get(id);
            if (f != null && f.isMouseOver(x, y)) {
                if (focused != null && focused != f) focused.setFocused(false);
                focused = f;
                f.setFocused(true);
                f.mouseClicked(click, doubled);
                return true;
            }
        }
        unfocus();
        if (click.button() == 0) {
            for (int i = regions.size() - 1; i >= 0; i--) {
                Region r = regions.get(i);
                if (r.r.has(x, y) && (r.clip == null || r.clip.has(x, y))) {
                    r.onPress.accept(x);
                    if (r.drag) dragging = r;
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) {
            dragging.onPress.accept(click.x());
            return true;
        }
        if (focused != null) return focused.mouseDragged(click, dx, dy);
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            dragging = null;
            ModConfig.save();
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        for (int i = scrollAreas.size() - 1; i >= 0; i--) {
            ScrollArea a = scrollAreas.get(i);
            if (a.r.has(mouseX, mouseY)) {
                int max = Math.max(0, a.contentH - a.r.h);
                int cur = scroll.getOrDefault(a.id, 0);
                scroll.put(a.id, Math.max(0, Math.min(max, cur - (int) (verticalAmount * 20))));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (capturing != null) {
            KeyBinding kb = capturing;
            capturing = null;
            if (input.isEscape()) {
                return true;
            }
            kb.setBoundKey(InputUtil.fromKeyCode(input));
            KeyBinding.updateKeysByCode();
            client.options.write();
            return true;
        }
        if (focused != null) {
            if (input.isEscape()) {
                unfocus();
                return true;
            }
            if (input.isEnter()) {
                unfocus();
                return true;
            }
            focused.keyPressed(input);
            return true;
        }
        if (Keys.OPEN.matchesKey(input)) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (focused != null) return focused.charTyped(input);
        return super.charTyped(input);
    }

    // ================================================================ помощники для вкладок

    public boolean hover(Rect r) {
        return r.has(mx, my) && (clip == null || clip.has(mx, my));
    }

    void region(Rect r, DoubleConsumer onPress) {
        regions.add(new Region(r, clip, onPress, false));
    }

    void tooltip(Rect r, String text) {
        if (text != null && hover(r)) tip = text;
    }

    public enum Style { NORMAL, ACCENT, DANGER, GHOST }

    /** Кнопка. Возвращает true, если под курсором. */
    boolean button(int x, int y, int w, int h, String label, Style style, boolean enabled, Runnable action) {
        Rect r = new Rect(x, y, w, h);
        boolean hov = enabled && hover(r);
        int bg, fg = Ui.TEXT;
        if (!enabled) {
            bg = 0xFF1A2027;
            fg = 0xFF55606B;
        } else {
            bg = switch (style) {
                case ACCENT -> hov ? Ui.ACCENT_HOVER : Ui.ACCENT_DARK;
                case DANGER -> hov ? Ui.DANGER : Ui.DANGER_DARK;
                case GHOST -> hov ? Ui.CARD_HOVER : 0x00000000;
                default -> hov ? Ui.CARD_HOVER : Ui.CARD;
            };
            if (style == Style.ACCENT && hov) fg = 0xFF06201C;
        }
        Ui.round(g, x, y, w, h, bg);
        if (style != Style.GHOST || hov) Ui.border(g, x, y, w, h, enabled && style == Style.ACCENT ? Ui.ACCENT : Ui.BORDER);
        String t = trim(label, w - 8);
        g.drawText(textRenderer, t, x + (w - textRenderer.getWidth(t)) / 2, y + (h - 8) / 2, fg, false);
        if (enabled) region(r, px -> action.run());
        return hov;
    }

    void toggle(int x, int y, int w, String label, boolean value, Consumer<Boolean> onChange) {
        Rect r = new Rect(x, y, w, 16);
        boolean hov = hover(r);
        int sx = x + w - 28;
        Ui.round(g, sx, y + 2, 26, 12, value ? Ui.ACCENT_DARK : 0xFF2A323B);
        int kx = value ? sx + 14 : sx + 2;
        Ui.round(g, kx, y + 3, 10, 10, value ? Ui.ACCENT : Ui.DIM);
        g.drawText(textRenderer, trim(label, w - 34), x, y + 4, hov ? Ui.TEXT : 0xFFC4CED8, false);
        region(r, p -> {
            onChange.accept(!value);
            ModConfig.save();
        });
    }

    void slider(int x, int y, int w, String label, double min, double max, double value, boolean integer, Consumer<Double> onChange) {
        String val = integer ? Integer.toString((int) Math.round(value)) : String.format("%.1f", value);
        g.drawText(textRenderer, label, x, y, 0xFFC4CED8, false);
        g.drawText(textRenderer, val, x + w - textRenderer.getWidth(val), y, Ui.ACCENT, false);
        int ty = y + 12;
        Rect r = new Rect(x, ty - 3, w, 12);
        Ui.round(g, x, ty, w, 4, 0xFF2A323B);
        double f = (value - min) / (max - min);
        int kx = x + (int) Math.round(f * (w - 6));
        Ui.round(g, x, ty, Math.max(2, kx - x + 3), 4, Ui.ACCENT_DARK);
        Ui.round(g, kx, ty - 2, 6, 8, hover(r) ? Ui.ACCENT_HOVER : Ui.ACCENT);
        regions.add(new Region(r, clip, mouseX -> {
            double t = Math.max(0, Math.min(1, (mouseX - x - 3) / (w - 6)));
            double v = min + t * (max - min);
            onChange.accept(integer ? (double) Math.round(v) : Math.round(v * 10) / 10.0);
        }, true));
    }

    /** Текстовое поле: создаётся один раз, дальше сохраняет текст между кадрами. */
    TextFieldWidget field(String id, int x, int y, int w, int h, String hint, String initial) {
        TextFieldWidget f = fields.get(id);
        if (f == null) {
            f = new TextFieldWidget(textRenderer, x, y, w, h, Text.empty());
            f.setMaxLength(512);
            f.setText(initial == null ? "" : initial);
            f.setCursorToStart(false);
            fields.put(id, f);
        }
        f.setPlaceholder(Text.literal(hint).formatted(net.minecraft.util.Formatting.DARK_GRAY));
        f.setX(x);
        f.setY(y);
        f.setWidth(w);
        f.setHeight(h);
        boolean visible = clip == null || (clip.has(x, y) && clip.has(x, y + h - 1));
        f.visible = visible;
        if (visible) {
            f.render(g, mx, my, dt);
            shown.add(id);
        }
        return f;
    }

    String fieldText(String id) {
        TextFieldWidget f = fields.get(id);
        return f == null ? "" : f.getText();
    }

    void setField(String id, String text) {
        TextFieldWidget f = fields.get(id);
        if (f != null) {
            f.setText(text);
            f.setCursorToStart(false);
        }
    }

    boolean fieldFocused(String id) {
        return focused != null && fields.get(id) == focused;
    }

    int intField(String id, int fallback) {
        try {
            return Integer.parseInt(fieldText(id).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Начало прокручиваемой области. Возвращает смещение. */
    int beginScroll(String id, int x, int y, int w, int h, int contentH) {
        Rect r = new Rect(x, y, w, h);
        scrollAreas.add(new ScrollArea(id, r, contentH));
        int max = Math.max(0, contentH - h);
        int off = Math.max(0, Math.min(max, scroll.getOrDefault(id, 0)));
        scroll.put(id, off);
        g.enableScissor(x, y, x + w, y + h);
        clip = r;
        return off;
    }

    void endScroll(String id) {
        g.disableScissor();
        ScrollArea a = null;
        for (ScrollArea s : scrollAreas) if (s.id.equals(id)) a = s;
        clip = null;
        if (a == null || a.contentH <= a.r.h) return;
        int off = scroll.getOrDefault(id, 0);
        int max = a.contentH - a.r.h;
        int bh = Math.max(14, a.r.h * a.r.h / a.contentH);
        int by = a.r.y + (int) ((a.r.h - bh) * (off / (double) max));
        g.fill(a.r.x + a.r.w - 3, a.r.y, a.r.x + a.r.w - 1, a.r.y + a.r.h, 0xFF222A33);
        g.fill(a.r.x + a.r.w - 3, by, a.r.x + a.r.w - 1, by + bh, Ui.DIM);
    }

    void resetScroll(String id) {
        scroll.put(id, 0);
    }

    // ---- текст

    String trim(String s, int maxW) {
        if (textRenderer.getWidth(s) <= maxW) return s;
        return textRenderer.trimToWidth(s, Math.max(0, maxW - textRenderer.getWidth("…"))) + "…";
    }

    void text(String s, int x, int y, int color) {
        g.drawText(textRenderer, s, x, y, color, false);
    }

    void textShadow(String s, int x, int y, int color) {
        g.drawText(textRenderer, s, x, y, color, true);
    }

    /** Перенос по словам; возвращает количество строк. maxLines <= 0 — без ограничения. */
    int wrapped(String s, int x, int y, int w, int color, int maxLines) {
        List<String> lines = wrap(s, w);
        int n = 0;
        for (String l : lines) {
            if (maxLines > 0 && n >= maxLines) break;
            String out = l;
            if (maxLines > 0 && n == maxLines - 1 && lines.size() > maxLines) out = trim(l + " …", w);
            g.drawText(textRenderer, out, x, y + n * 11, color, false);
            n++;
        }
        return n;
    }

    List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (textRenderer.getWidth(test) > w && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(test);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    void drawItem(String id, int x, int y) {
        Identifier i = Identifier.tryParse(id);
        var item = i == null ? Items.BARRIER : Registries.ITEM.getOptionalValue(i).orElse(Items.BARRIER);
        g.drawItem(new ItemStack(item), x, y);
    }

    TextRenderer font() {
        return textRenderer;
    }

    void card(int x, int y, int w, int h, boolean selected, boolean hovered) {
        Ui.round(g, x, y, w, h, selected ? 0xFF22303A : (hovered ? Ui.CARD_HOVER : Ui.CARD));
        if (selected) {
            c4(x, y, h);
        }
    }

    private void c4(int x, int y, int h) {
        g.fill(x, y + 2, x + 2, y + h - 2, Ui.ACCENT);
    }

    void saveMacros() {
        recorderTab.flush();
    }
}

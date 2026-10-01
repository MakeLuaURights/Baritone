package dev.baritonestudio.macro;

import dev.baritonestudio.config.ModConfig;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.minecraft.world.debug.gizmo.VisibilityConfigurable;

/**
 * Предпросмотр повтора: рамки на месте будущих действий.
 * Красный – сломать, зелёный – поставить, синий – клик/использование,
 * жёлтый – надпись на табличке, голубой – путь. Оранжевый – место занято/нечего ломать.
 */
public final class Preview {
    private static final int RED = 0xFFFF5555, GREEN = 0xFF55E07A, BLUE = 0xFF5AA9FF, YELLOW = 0xFFFFD84D,
            CYAN = 0xFF4DE3E3, ORANGE = 0xFFFF9F43, GRAY = 0xFF8A8F98, WHITE = 0xFFFFFFFF;

    private Preview() {}

    private static int alpha(int color, int a) {
        return (a << 24) | (color & 0xFFFFFF);
    }

    private static void vis(VisibilityConfigurable v) {
        if (ModConfig.get().previewThroughWalls) v.ignoreOcclusion();
    }

    private static void box(Box b, int color, int fillAlpha, float width) {
        vis(GizmoDrawing.box(b, fillAlpha > 0 ? DrawStyle.filledAndStroked(color, width, alpha(color, fillAlpha)) : DrawStyle.stroked(color, width)));
    }

    /**
     * Нарисовать одну цель.
     *
     * @param bright ярче и с подписями (цель под прицелом) либо приглушённо (уже добавленная)
     * @param index  номер цели в списке (для подписи), либо 0 если не нужен
     */
    public static void draw(Macro m, Target target, boolean bright, int index) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld w = mc.world;
        if (w == null || m == null) return;
        Transform tr = new Transform(target);
        boolean labels = ModConfig.get().previewLabels && bright;
        int fill = bright ? 70 : 38;
        float width = bright ? 2.5f : 1.6f;
        int counter = m.counterStart + Math.max(0, index - 1) * m.counterStep;

        Vec3d prev = null;
        int n = 0;
        List<Step> steps = m.steps;
        for (Step s : steps) {
            n++;
            BlockPos p = tr.pos(s);
            Vec3d anchor;
            switch (s.type) {
                case MOVE -> {
                    box(new Box(p.getX() + 0.3, p.getY(), p.getZ() + 0.3, p.getX() + 0.7, p.getY() + 0.12, p.getZ() + 0.7), CYAN, fill, width);
                    anchor = new Vec3d(p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5);
                }
                case BREAK -> {
                    BlockState st = w.getBlockState(p);
                    boolean nothing = st.isAir() || st.isLiquid();
                    box(new Box(p).expand(0.003), nothing ? GRAY : RED, fill, width);
                    if (labels) label(n + " X", p, 0, nothing ? GRAY : RED);
                    anchor = Vec3d.ofCenter(p);
                }
                case USE -> {
                    if (s.placed) {
                        BlockPos pp = tr.placedPos(s);
                        BlockState cur = w.getBlockState(pp);
                        boolean blocked = !cur.isAir() && !cur.isReplaceable();
                        box(new Box(pp).expand(0.003), blocked ? ORANGE : GREEN, fill, width);
                        if (labels) label(n + " +", pp, 0, blocked ? ORANGE : GREEN);
                        anchor = Vec3d.ofCenter(pp);
                    } else {
                        box(new Box(p).expand(0.003), BLUE, fill, width);
                        if (labels) label(n + " >", p, 0, BLUE);
                        anchor = Vec3d.ofCenter(p);
                    }
                }
                case SIGN -> {
                    box(new Box(p).expand(0.03), YELLOW, bright ? 40 : 20, width);
                    if (labels) {
                        String first = Transform.fill(s.lines != null ? firstNonEmpty(s.lines) : "", counter);
                        label("[T] " + first, p, 1, YELLOW);
                    }
                    anchor = Vec3d.ofCenter(p);
                }
                default -> anchor = Vec3d.ofCenter(p);
            }
            if (prev != null && bright) {
                vis(GizmoDrawing.line(prev, anchor, alpha(WHITE, 130), 1.5f));
            }
            prev = anchor;
        }
        if (index > 0) {
            // номер цели над опорным блоком
            label("#" + index, tr.ref, 3, WHITE);
        }
    }

    private static String firstNonEmpty(String[] lines) {
        for (String l : lines) if (l != null && !l.isEmpty()) return l;
        return "";
    }

    private static void label(String text, BlockPos at, int row, int color) {
        vis(GizmoDrawing.blockLabel(text, at, row, color, 0.9f));
    }

    /** Нарисовать путь навигатора линиями. */
    public static void drawPath(List<BlockPos> nodes, int fromIndex) {
        Vec3d prev = null;
        for (int i = Math.max(0, fromIndex); i < nodes.size(); i++) {
            BlockPos p = nodes.get(i);
            Vec3d v = new Vec3d(p.getX() + 0.5, p.getY() + 0.12, p.getZ() + 0.5);
            if (prev != null) vis(GizmoDrawing.line(prev, v, alpha(0xFFE056E0, 220), 3.0f));
            prev = v;
        }
    }
}

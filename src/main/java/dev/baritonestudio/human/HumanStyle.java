package dev.baritonestudio.human;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.util.Storage;
import java.util.List;
import java.util.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Воспроизведение записанного почерка: для каждого нового дерева бот случайно выбирает
 * один из записанных профилей (не повторяя недавние) и рубит это дерево «как вы».
 */
public final class HumanStyle {
    private static final Random RND = new Random();
    private static HumanProfile current;
    private static Vec3d treeCenter;
    private static long lastActive = -10_000;
    private static int lastIdx = -1, prevIdx = -1;
    private static boolean newTree;

    private HumanStyle() {}

    public static Random rnd() {
        return RND;
    }

    /** Включён ли режим записанных профилей и есть ли что воспроизводить. */
    public static boolean enabled() {
        ModConfig c = ModConfig.get();
        return c.humanMode && c.humanProfiles && !HumanProfiles.all().isEmpty();
    }

    /** Профиль для рубки бревна в этой точке (выбирается заново для каждого нового дерева). */
    public static HumanProfile forLog(BlockPos pos, long time) {
        if (!enabled()) return null;
        Vec3d c = Vec3d.ofCenter(pos);
        if (current == null || treeCenter == null || c.distanceTo(treeCenter) > 8 || time - lastActive > 200) {
            pickNew(time);
            treeCenter = c;
            newTree = true;
        }
        lastActive = time;
        return current;
    }

    /** Текущий выбранный профиль без смены дерева (null если не рубим). */
    public static HumanProfile current(long time) {
        return enabled() && current != null && time - lastActive <= 200 ? current : null;
    }

    /** Было ли начало нового дерева (флаг сбрасывается после запроса). */
    public static boolean isNewTree() {
        boolean r = newTree;
        newTree = false;
        return r;
    }

    public static int currentIndex() {
        return lastIdx;
    }

    private static void pickNew(long time) {
        List<HumanProfile> all = HumanProfiles.all();
        int n = all.size();
        java.util.List<Integer> options = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (n > 2 && (i == lastIdx || i == prevIdx)) continue; // не повторяем два последних стиля
            if (n == 2 && i == lastIdx) continue;
            options.add(i);
        }
        if (options.isEmpty()) options.add(0);
        int idx = options.get(RND.nextInt(options.size()));
        prevIdx = lastIdx;
        lastIdx = idx;
        current = all.get(idx);
        Storage.LOG.info("HumanStyle: новое дерево – профиль #{} из {} ({})", idx + 1, n, current.summary());
    }

    /** Точка прицеливания внутри блока по статистике профиля. */
    public static Vec3d aimPoint(HumanProfile p, BlockPos b) {
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            v[i] = Math.max(0.1, Math.min(0.9, p.aimOffset[i] + RND.nextGaussian() * p.aimOffsetStd[i]));
        }
        return new Vec3d(b.getX() + v[0], b.getY() + v[1], b.getZ() + v[2]);
    }

    public static void reset() {
        current = null;
        treeCenter = null;
        lastIdx = -1;
        prevIdx = -1;
    }
}

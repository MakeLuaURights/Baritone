package dev.baritonestudio.path;

/**
 * Стоимости действий в игровых тиках (как в Baritone: время — самая честная «валюта» для поиска пути).
 * Скорости игрока в блоках/сек: ходьба 4.317, спринт 5.612, плавание 2.2, лестница вверх 2.35 / вниз 3.0.
 */
public final class Costs {
    public static final double INF = 1_000_000;

    public static final double WALK = 20.0 / 4.317;      // 4.633 тика на блок
    public static final double SPRINT = 20.0 / 5.612;    // 3.564
    public static final double SNEAK = 20.0 / 1.3;
    public static final double WALK_WATER = 20.0 / 2.2;  // 9.09
    public static final double LADDER_UP = 20.0 / 2.35;  // 8.51
    public static final double LADDER_DOWN = 20.0 / 3.0; // 6.67
    public static final double SQRT2 = Math.sqrt(2);
    /** Сход с края блока перед падением и центровка после приземления. */
    public static final double WALK_OFF = WALK * 0.8;
    public static final double CENTER_AFTER_FALL = WALK - WALK_OFF;
    /** Нижняя оценка стоимости блока при эвристике (чуть меньше спринта – оценка остаётся допустимой). */
    public static final double HEURISTIC = 3.563;
    /** Дополнительный штраф за поломку блока (помимо времени ломания). */
    public static final double BREAK_PENALTY = 2.0;
    /** Постановка блока (столб/мост): Baritone берёт 20 тиков. */
    public static final double PLACE = 20.0;
    /** Штраф за прыжок (парkour). */
    public static final double JUMP_PENALTY = 2.0;

    /** Время падения на n блоков (тиков), по физике игрока: v(t) = (0.98^t - 1) * -3.92. */
    public static final double[] FALL = new double[257];
    public static final double JUMP_ONE_BLOCK;
    /** Минимальная стоимость одного блока спуска (для эвристики). */
    public static final double DOWN_PER_BLOCK;

    static {
        for (int i = 0; i < FALL.length; i++) FALL[i] = ticksToFall(i);
        JUMP_ONE_BLOCK = ticksToFall(1.25) - ticksToFall(0.25);
        double min = Double.MAX_VALUE;
        for (int n = 1; n <= 10; n++) min = Math.min(min, FALL[n] / n);
        DOWN_PER_BLOCK = min;
    }

    private static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    private static double ticksToFall(double distance) {
        if (distance == 0) return 0;
        double left = distance;
        int ticks = 0;
        while (true) {
            double v = velocity(ticks);
            if (left <= v) return ticks + left / v;
            left -= v;
            ticks++;
        }
    }

    private Costs() {}
}

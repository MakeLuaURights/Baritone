package dev.baritonestudio.human;

import java.util.Random;

/**
 * «Почерк» игрока при рубке одного дерева: записан с настоящей игры и потом воспроизводится ботом.
 * Все величины — средние по брёвнам этого дерева.
 */
public class HumanProfile {
    public static final int CURVE_POINTS = 17;

    public String created = "";
    /** Сколько брёвен было в записи. */
    public int logs;
    /** Время от наведения прицела на бревно до первого удара, тиков (и разброс). */
    public float reaction = 8, reactionSpread = 3;
    /** Расстояние от глаз до точки удара, блоков. */
    public float hitDistance = 3.0f;
    /** Пауза между сломанным бревном и началом следующего движения, тиков (и разброс). */
    public float think = 6, thinkSpread = 3;
    /** Кривая наведения: прогресс поворота (0..~1.05) в 17 равноотстоящих моментах времени; значения >1 – перелёт. */
    public float[] aimCurve = defaultCurve();
    /** Средняя скорость поворота, градусов в тик, и самая быстрая запись по длительности (тиков). */
    public float aimSpeed = 12, aimMinTicks = 3;
    /** Дрожание прицела во время удара (среднеквадратичное изменение угла за тик). */
    public float jitterYaw = 0.12f, jitterPitch = 0.09f;
    /** Куда в блоке целится игрок (доли 0..1) и разброс. */
    public float[] aimOffset = {0.5f, 0.5f, 0.5f};
    public float[] aimOffsetStd = {0.15f, 0.15f, 0.15f};
    /** Как часто игрок сначала смотрит вверх на крону и насколько (градусов). */
    public float lookUpProb = 0, lookUpDeg = 0;
    /** Для информации: время ломания бревна, тиков, и чувствительность мыши при записи. */
    public float breakTicks = 0, mouseSens = 0.5f;

    public static float[] defaultCurve() {
        float[] c = new float[CURVE_POINTS];
        for (int i = 0; i < CURVE_POINTS; i++) {
            double u = i / (double) (CURVE_POINTS - 1);
            c[i] = (float) (1 - Math.pow(1 - u, 2.2)); // быстрый старт, плавное торможение
        }
        return c;
    }

    /** Прогресс поворота в момент u (0..1 длительности). */
    public float curveAt(double u) {
        if (aimCurve == null || aimCurve.length < 2) return (float) Math.min(1, Math.max(0, u));
        double x = Math.max(0, Math.min(1, u)) * (aimCurve.length - 1);
        int i = (int) Math.floor(x);
        if (i >= aimCurve.length - 1) return aimCurve[aimCurve.length - 1];
        double f = x - i;
        return (float) (aimCurve[i] * (1 - f) + aimCurve[i + 1] * f);
    }

    public int sampleReaction(Random r) {
        return Math.max(0, Math.round(reaction + (float) r.nextGaussian() * reactionSpread));
    }

    public int sampleThink(Random r) {
        return Math.max(0, Math.round(think + (float) r.nextGaussian() * thinkSpread));
    }

    /** Длительность поворота на angle градусов, тиков. */
    public int aimTicks(double angle) {
        return (int) Math.max(Math.round(aimMinTicks), Math.min(40, Math.round(angle / Math.max(1.5, aimSpeed))));
    }

    public String summary() {
        return String.format("брёвен %d · реакция %.0f т. · дистанция %.1f · пауза %.0f т.", logs, reaction, hitDistance, think);
    }
}

package dev.baritonestudio.task;

import dev.baritonestudio.config.ModConfig;
import java.util.Random;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Human-Mode: небольшие случайные «человеческие» отклонения в наведении мыши, ходьбе,
 * добыче и строительстве. Все значения масштабируются ползунком силы.
 */
public final class Human {
    private static final Human INSTANCE = new Human();

    public static Human get() {
        return INSTANCE;
    }

    private final Random rnd = new Random();
    // медленно блуждающий шум прицела
    private float noiseYaw, noisePitch;
    private float noiseVelYaw, noiseVelPitch;
    // ходьба
    private int pauseLeft, untilPause = 200;
    private boolean sprintAllowed = true;
    private int untilSprintFlip = 120;
    // добыча
    private int hiccupLeft, untilHiccup = 150;

    public boolean on() {
        return ModConfig.get().humanMode;
    }

    public float k() {
        return ModConfig.get().humanIntensity;
    }

    public Random rnd() {
        return rnd;
    }

    /** base + случайная добавка до 60% * сила (в тиках). */
    public int jitter(int base) {
        if (!on()) return base;
        return base + rnd.nextInt(Math.max(1, Math.round(base * 0.6f * k()) + 1));
    }

    /** Время реакции перед началом нового действия, тиков. */
    public int reaction() {
        if (!on()) return 0;
        return 3 + rnd.nextInt(Math.max(1, Math.round(7 * k())));
    }

    // ------------------------------------------------------------ прицел

    /** Вызывается раз в тик, пока бот двигает прицел. */
    public void updateNoise() {
        float k = k();
        noiseVelYaw = noiseVelYaw * 0.85f + (rnd.nextFloat() - 0.5f) * 0.25f * k;
        noiseVelPitch = noiseVelPitch * 0.85f + (rnd.nextFloat() - 0.5f) * 0.18f * k;
        noiseYaw = clamp(noiseYaw * 0.96f + noiseVelYaw, 1.4f * k);
        noisePitch = clamp(noisePitch * 0.96f + noiseVelPitch, 1.0f * k);
    }

    public float noiseYaw() {
        return noiseYaw;
    }

    public float noisePitch() {
        return noisePitch;
    }

    private static float clamp(float v, float m) {
        return Math.max(-m, Math.min(m, v));
    }

    /** Доля оставшегося угла, на которую поворачиваем за тик (плавное замедление к цели). */
    public float turnGain() {
        return 0.16f + rnd.nextFloat() * 0.2f;
    }

    /** Шаг «мыши» в градусах для текущей чувствительности: реальная мышь двигает прицел только кратными шагами. */
    public float mouseStep() {
        double sens = MinecraftClient.getInstance().options.getMouseSensitivity().getValue();
        double d = sens * 0.6 + 0.2;
        double f = d * d * d * 8.0;
        return (float) Math.max(0.01, f * 0.15);
    }

    /** Точка прицеливания внутри блока вместо идеального центра. */
    public Vec3d aimPoint(Vec3d center, long seed) {
        if (!on()) return center;
        Random r = new Random(seed);
        double s = 0.28 * Math.min(1.4, k());
        return center.add((r.nextDouble() - 0.5) * 2 * s, (r.nextDouble() - 0.5) * 2 * s, (r.nextDouble() - 0.5) * 2 * s);
    }

    // ------------------------------------------------------------ ходьба

    /** true — нужно на мгновение остановиться (как человек, который отвлёкся). */
    public boolean walkPause(boolean allowed) {
        if (!on()) return false;
        if (pauseLeft > 0) {
            pauseLeft--;
            return true;
        }
        if (!allowed) return false;
        if (--untilPause <= 0) {
            pauseLeft = 4 + rnd.nextInt(Math.max(1, Math.round(18 * k())));
            untilPause = Math.round((140 + rnd.nextInt(280)) / k());
        }
        return false;
    }

    /** Иногда человек перестаёт бежать на несколько секунд. */
    public boolean sprintAllowed() {
        if (!on()) return true;
        if (--untilSprintFlip <= 0) {
            sprintAllowed = rnd.nextFloat() < 0.8f;
            untilSprintFlip = 40 + rnd.nextInt(160);
        }
        return sprintAllowed;
    }

    /** Расстояние до препятствия, на котором начинается прыжок. */
    public double jumpDistance() {
        return on() ? 1.1 + rnd.nextDouble() * 0.35 : 1.45;
    }

    // ------------------------------------------------------------ добыча

    /** Редкие «заминки» при ломании блока. */
    public boolean mineHiccup() {
        if (!on()) return false;
        if (hiccupLeft > 0) {
            hiccupLeft--;
            return true;
        }
        if (--untilHiccup <= 0) {
            hiccupLeft = 2 + rnd.nextInt(Math.max(1, Math.round(7 * k())));
            untilHiccup = 80 + rnd.nextInt(200);
        }
        return false;
    }
}

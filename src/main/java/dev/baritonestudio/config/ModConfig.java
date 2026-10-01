package dev.baritonestudio.config;

import dev.baritonestudio.util.Storage;
import java.nio.file.Path;

/** Настройки мода (config/baritonestudio/config.json). */
public class ModConfig {
    /** Максимальная дальность взаимодействия с блоками (в блоках). */
    public double reach = 4.4;
    /** Скорость поворота головы, градусов за тик. 0 = мгновенно. */
    public float rotateSpeed = 40f;
    /** Минимальная пауза между действиями записи, тиков. */
    public int actionDelay = 3;
    /** Радиус поиска блоков для добычи. */
    public int searchRadius = 32;
    /** Можно ли прокладывать путь, ломая блоки (прокапывание). */
    public boolean allowBreak = true;
    /** Можно ли ставить блоки-опоры (столб вверх). */
    public boolean allowScaffold = true;
    /** Максимальная безопасная высота падения. */
    public int maxFall = 3;
    /** Остановка, если здоровье упало до этого значения (в половинках сердец). 0 = выкл. */
    public int stopHealth = 6;
    /** Бежать (спринт), когда это возможно. */
    public boolean sprint = true;
    /** Подбирать выпавшие предметы после добычи. */
    public boolean collectDrops = true;
    /** Остановка при полном инвентаре. */
    public boolean stopOnFullInventory = true;
    /** Показывать панель состояния на экране. */
    public boolean showHud = true;
    /** Рамки предпросмотра видны сквозь блоки. */
    public boolean previewThroughWalls = true;
    /** Показывать номера шагов над рамками предпросмотра. */
    public boolean previewLabels = true;
    /** Сколько секунд ждать нужный предмет при повторе записи, прежде чем остановиться. */
    public int itemWaitSeconds = 30;

    private static ModConfig instance = new ModConfig();

    public static ModConfig get() {
        return instance;
    }

    private static Path file() {
        return Storage.dir().resolve("config.json");
    }

    public static void load() {
        ModConfig c = Storage.read(file(), ModConfig.class);
        instance = c != null ? c : new ModConfig();
        instance.clamp();
        save();
    }

    public static void save() {
        Storage.write(file(), instance);
    }

    public void clamp() {
        reach = Math.max(2.0, Math.min(4.8, reach));
        rotateSpeed = Math.max(0f, Math.min(180f, rotateSpeed));
        actionDelay = Math.max(1, Math.min(40, actionDelay));
        searchRadius = Math.max(4, Math.min(96, searchRadius));
        maxFall = Math.max(1, Math.min(10, maxFall));
        stopHealth = Math.max(0, Math.min(19, stopHealth));
        itemWaitSeconds = Math.max(1, Math.min(600, itemWaitSeconds));
    }
}

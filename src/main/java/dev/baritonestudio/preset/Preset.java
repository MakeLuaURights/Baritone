package dev.baritonestudio.preset;

import dev.baritonestudio.task.BlockMatcher;
import dev.baritonestudio.task.CollectTask;
import dev.baritonestudio.task.GotoTask;
import dev.baritonestudio.task.MineTask;
import dev.baritonestudio.task.Task;
import dev.baritonestudio.path.Goal;
import dev.baritonestudio.util.L;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Готовая настройка автоматической работы. Встроенные и пользовательские хранятся в одном виде. */
public class Preset {
    public enum Type { MINE, TUNNEL, LEVEL, COLLECT }

    public String id = "";
    public String name = "";
    public String description = "";
    /** Идентификатор предмета-иконки, например minecraft:stone. */
    public String icon = "minecraft:stone";
    public Type type = Type.MINE;
    /** Блоки для добычи: id, #тег или id[свойство=значение]. */
    public List<String> blocks = new ArrayList<>();
    /** MINE: сколько блоков добыть (0 = без ограничения). TUNNEL: длина. LEVEL: целевая высота Y. */
    public int count = 0;
    /** Радиус поиска (0 = из настроек). */
    public int radius = 0;
    /** Пересаживать урожай после сбора. */
    public boolean replant = false;
    public transient boolean builtin = false;

    public Preset copy() {
        Preset p = new Preset();
        p.id = id;
        p.name = name;
        p.description = description;
        p.icon = icon;
        p.type = type;
        p.blocks = new ArrayList<>(blocks);
        p.count = count;
        p.radius = radius;
        p.replant = replant;
        return p;
    }

    public String displayName() {
        if (builtin && L.has("preset." + id)) return L.t("preset." + id);
        return name == null || name.isBlank() ? id : name;
    }

    public String displayDescription() {
        if (builtin && L.has("preset." + id + ".desc")) return L.t("preset." + id + ".desc");
        return description == null ? "" : description;
    }

    /** Создать задачу для текущего положения игрока. */
    public Task createTask() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String title = displayName();
        switch (type) {
            case MINE:
                return new MineTask(title, BlockMatcher.parse(blocks), count, radius, replant);
            case COLLECT:
                return new CollectTask(radius > 0 ? radius : 16);
            case TUNNEL: {
                BlockPos p = mc.player.getBlockPos();
                Direction d = mc.player.getHorizontalFacing();
                int len = Math.max(1, count);
                BlockPos end = p.offset(d, len);
                return new GotoTask(title, new Goal.Block(end), true);
            }
            case LEVEL:
                return new GotoTask(title, new Goal.Level(count), true);
            default:
                throw new IllegalStateException();
        }
    }
}

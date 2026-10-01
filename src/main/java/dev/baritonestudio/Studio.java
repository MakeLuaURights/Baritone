package dev.baritonestudio;

import dev.baritonestudio.macro.Macro;
import dev.baritonestudio.macro.MacroStore;
import dev.baritonestudio.macro.MacroTask;
import dev.baritonestudio.macro.Preview;
import dev.baritonestudio.macro.Recorder;
import dev.baritonestudio.macro.Step;
import dev.baritonestudio.macro.Target;
import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.path.Path;
import dev.baritonestudio.preset.Preset;
import dev.baritonestudio.task.GotoTask;
import dev.baritonestudio.task.Task;
import dev.baritonestudio.task.TaskManager;
import dev.baritonestudio.util.L;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Центральное состояние мода: задачи, запись, цели повтора, выбранная запись. */
public final class Studio {
    private static Studio instance;

    public static Studio get() {
        if (instance == null) instance = new Studio();
        return instance;
    }

    private final MinecraftClient mc = MinecraftClient.getInstance();
    public final TaskManager tasks = new TaskManager();
    public final Recorder recorder = new Recorder();

    /** Выбранная запись и места, где её нужно повторить. */
    public Macro macro;
    public final List<Target> targets = new ArrayList<>();
    public boolean previewEnabled = true;

    /** Режим выбора мест: мир показывает предпросмотр под прицелом. */
    public boolean targeting;
    public int manualRot;
    public Target hover;

    private List<Macro> library;

    private Studio() {}

    // ------------------------------------------------------------ библиотека записей

    public List<Macro> library() {
        if (library == null) reloadLibrary();
        return library;
    }

    public void reloadLibrary() {
        library = new ArrayList<>(MacroStore.list());
    }

    public void select(Macro m) {
        if (macro != m) {
            macro = m;
            targets.clear();
            manualRot = 0;
        }
    }

    // ------------------------------------------------------------ задачи

    public void startPreset(Preset p, Integer countOverride, Integer radiusOverride) {
        if (mc.player == null) return;
        Preset run = p.copy();
        if (countOverride != null) run.count = countOverride;
        if (radiusOverride != null) run.radius = radiusOverride;
        run.builtin = p.builtin;
        run.id = p.id;
        run.name = p.name;
        tasks.start(run.createTask());
    }

    public void startGoto(int x, int y, int z) {
        tasks.start(new GotoTask(L.t("task.goto", x, y, z), new Goal.Block(x, y, z), false));
    }

    public void stopAll(String reason) {
        tasks.stop(reason);
    }

    // ------------------------------------------------------------ запись

    public void startRecording() {
        if (mc.player == null) return;
        recorder.start();
        say(Formatting.RED, L.t("msg.rec_started"));
    }

    /** Остановить запись; сохраняет и выбирает новую запись. */
    public Macro stopRecording(String name) {
        Macro m = recorder.stop(uniqueName(name));
        if (m == null) {
            say(Formatting.YELLOW, L.t("msg.rec_empty"));
            return null;
        }
        MacroStore.save(m);
        reloadLibrary();
        select(m);
        say(Formatting.GREEN, L.t("msg.rec_saved", m.name, m.actionCount()));
        return m;
    }

    /** Имя, которого нет в библиотеке (иначе файл перезаписал бы чужую запись). */
    public String uniqueName(String wanted) {
        List<String> names = new ArrayList<>();
        for (Macro m : library()) names.add(m.name);
        String name = wanted;
        int n = 2;
        while (names.contains(name)) name = wanted + " (" + n++ + ")";
        return name;
    }

    public String nextMacroName() {
        int n = library().size() + 1;
        List<String> names = new ArrayList<>();
        for (Macro m : library()) names.add(m.name);
        String base = L.t("rec.default_name");
        String name;
        do {
            name = base + " " + n++;
        } while (names.contains(name));
        return name;
    }

    // ------------------------------------------------------------ цели повтора

    public void beginTargeting() {
        if (macro == null) return;
        targeting = true;
        mc.setScreen(null);
        say(Formatting.AQUA, L.t("msg.targeting_help"));
    }

    public void addSeries(int count, int dx, int dy, int dz) {
        if (targets.isEmpty() || count <= 0) return;
        Target last = targets.get(targets.size() - 1);
        for (int i = 1; i <= count; i++) {
            targets.add(new Target(last.ref().add(dx * i, dy * i, dz * i), last.rot()));
        }
    }

    public void runMacro() {
        if (macro == null || targets.isEmpty()) return;
        targeting = false;
        tasks.start(new MacroTask(macro.copy(), new ArrayList<>(targets)));
    }

    /** Какие предметы и сколько нужно для повтора по всем целям. */
    public Map<Item, Integer> requiredItems() {
        Map<Item, Integer> need = new LinkedHashMap<>();
        if (macro == null) return need;
        int reps = Math.max(1, targets.size());
        for (Step s : macro.steps) {
            if (s.type == Step.Type.USE && s.placed) {
                Identifier id = Identifier.tryParse(s.item);
                if (id == null) continue;
                Item it = Registries.ITEM.getOptionalValue(id).orElse(null);
                if (it != null) need.merge(it, reps, Integer::sum);
            }
        }
        return need;
    }

    // ------------------------------------------------------------ тик

    private int autoRot() {
        if (macro == null || mc.player == null) return 0;
        int look = Direction.fromHorizontalDegrees(mc.player.getYaw()).getHorizontalQuarterTurns();
        return ((look - macro.facing) % 4 + 4) % 4;
    }

    public void tick() {
        if (mc.player == null || mc.world == null) return;

        handleKeys();
        tasks.tick();
        recorder.tick();

        // цель под прицелом
        hover = null;
        if (targeting && macro != null && mc.getCameraEntity() != null) {
            HitResult hr = mc.getCameraEntity().raycast(64.0, 0f, false);
            if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
                hover = new Target(bhr.getBlockPos(), autoRot() + manualRot);
            }
        }

        drawWorldOverlays();
    }

    private void handleKeys() {
        boolean noScreen = mc.currentScreen == null;
        while (Keys.OPEN.wasPressed()) {
            if (noScreen) {
                targeting = false;
                mc.setScreen(new dev.baritonestudio.gui.StudioScreen());
                noScreen = false;
            }
        }
        while (Keys.STOP.wasPressed()) {
            if (tasks.busy()) stopAll(L.t("msg.stopped"));
            else if (targeting) {
                targeting = false;
                say(Formatting.YELLOW, L.t("msg.targeting_off"));
            }
        }
        while (Keys.RECORD.wasPressed()) {
            if (!noScreen) continue;
            if (recorder.active()) stopRecording(nextMacroName());
            else startRecording();
        }
        if (targeting && noScreen) {
            while (Keys.TARGET_ADD.wasPressed()) {
                if (hover != null) {
                    targets.add(hover);
                    say(Formatting.GREEN, L.t("msg.target_added", targets.size()));
                }
            }
            while (Keys.TARGET_ROTATE.wasPressed()) {
                manualRot = (manualRot + 1) % 4;
            }
            while (Keys.TARGET_UNDO.wasPressed()) {
                if (!targets.isEmpty()) targets.remove(targets.size() - 1);
            }
        } else {
            while (Keys.TARGET_ADD.wasPressed()) {}
            while (Keys.TARGET_ROTATE.wasPressed()) {}
            while (Keys.TARGET_UNDO.wasPressed()) {}
        }
    }

    private void drawWorldOverlays() {
        // маршрут текущей задачи
        Task t = tasks.current();
        if (t != null) {
            Navigator nav = t.navigator();
            if (nav != null) {
                Path p = nav.currentPath();
                if (p != null) Preview.drawPath(p.nodes(), 0);
            }
        }
        if (!previewEnabled || macro == null) return;
        boolean running = t instanceof MacroTask;
        if (running) return; // во время работы лишнее
        // на очень длинных сериях рисуем только ближайшие цели, чтобы не просаживать FPS
        int drawn = 0;
        BlockPos me = mc.player.getBlockPos();
        for (int i = 0; i < targets.size() && drawn < 40; i++) {
            Target tg = targets.get(i);
            if (targets.size() > 40 && tg.ref().getSquaredDistance(me) > 80 * 80) continue;
            Preview.draw(macro, tg, false, i + 1);
            drawn++;
        }
        if (hover != null) Preview.draw(macro, hover, true, targets.size() + 1);
    }

    public void onDisconnect() {
        tasks.stop(null);
        recorder.cancel();
        targeting = false;
        targets.clear();
        hover = null;
    }

    public void say(Formatting color, String msg) {
        tasks.say(color, msg);
    }

    public BlockPos playerPos() {
        return mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
    }
}

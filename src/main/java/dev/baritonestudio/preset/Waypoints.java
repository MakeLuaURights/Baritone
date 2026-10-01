package dev.baritonestudio.preset;

import com.google.gson.reflect.TypeToken;
import dev.baritonestudio.util.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Сохранённые места (как «wp» в Baritone). Хранятся в config/baritonestudio/waypoints.json. */
public final class Waypoints {
    public static class Waypoint {
        public String name = "";
        public int x, y, z;
        public String dim = "";
    }

    private static List<Waypoint> list;

    private Waypoints() {}

    private static Path file() {
        return Storage.dir().resolve("waypoints.json");
    }

    public static List<Waypoint> all() {
        if (list == null) {
            list = new ArrayList<>();
            if (Files.isRegularFile(file())) {
                try (var r = Files.newBufferedReader(file())) {
                    List<Waypoint> l = Storage.GSON.fromJson(r, new TypeToken<List<Waypoint>>() {}.getType());
                    if (l != null) list = new ArrayList<>(l);
                } catch (Exception e) {
                    Storage.LOG.error("Не удалось прочитать waypoints.json", e);
                }
            }
        }
        return list;
    }

    private static void save() {
        Storage.write(file(), all());
    }

    public static String currentDim() {
        var w = MinecraftClient.getInstance().world;
        return w == null ? "" : w.getRegistryKey().getValue().toString();
    }

    public static Waypoint find(String name) {
        for (Waypoint w : all()) if (w.name.equalsIgnoreCase(name)) return w;
        return null;
    }

    public static void set(String name, BlockPos p) {
        Waypoint w = find(name);
        if (w == null) {
            w = new Waypoint();
            w.name = name;
            all().add(w);
        }
        w.x = p.getX();
        w.y = p.getY();
        w.z = p.getZ();
        w.dim = currentDim();
        save();
    }

    public static void remove(Waypoint w) {
        all().remove(w);
        save();
    }
}

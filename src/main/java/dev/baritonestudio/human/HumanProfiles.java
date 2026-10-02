package dev.baritonestudio.human;

import com.google.gson.reflect.TypeToken;
import dev.baritonestudio.util.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Записанные профили рубки деревьев (config/baritonestudio/human_profiles.json). */
public final class HumanProfiles {
    public static final int MAX = 10;
    private static List<HumanProfile> list;

    private HumanProfiles() {}

    private static Path file() {
        return Storage.dir().resolve("human_profiles.json");
    }

    public static List<HumanProfile> all() {
        if (list == null) {
            list = new ArrayList<>();
            if (Files.isRegularFile(file())) {
                try (var r = Files.newBufferedReader(file())) {
                    List<HumanProfile> l = Storage.GSON.fromJson(r, new TypeToken<List<HumanProfile>>() {}.getType());
                    if (l != null) list = new ArrayList<>(l);
                } catch (Exception e) {
                    Storage.LOG.error("Не удалось прочитать human_profiles.json", e);
                }
            }
        }
        return list;
    }

    public static void save() {
        Storage.write(file(), all());
    }

    public static void add(HumanProfile p) {
        all().add(p);
        while (all().size() > MAX) all().remove(0);
        save();
    }

    public static void clear() {
        all().clear();
        save();
    }
}

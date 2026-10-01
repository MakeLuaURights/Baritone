package dev.baritonestudio.macro;

import dev.baritonestudio.util.Storage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Сохранение записей в config/baritonestudio/macros/*.json. */
public final class MacroStore {
    private MacroStore() {}

    private static Path dir() {
        return Storage.sub("macros");
    }

    public static List<Macro> list() {
        List<Macro> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir())) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                Macro m = Storage.read(p, Macro.class);
                if (m != null && m.steps != null) {
                    if (m.name == null || m.name.isEmpty()) m.name = p.getFileName().toString().replace(".json", "");
                    for (Step st : m.steps) {
                        // защита от ручной правки файла: у табличек всегда ровно 4 строки
                        String[] fixed = {"", "", "", ""};
                        if (st.lines != null) {
                            for (int i = 0; i < 4 && i < st.lines.length; i++) fixed[i] = st.lines[i] == null ? "" : st.lines[i];
                        }
                        st.lines = fixed;
                        if (st.item == null) st.item = "minecraft:air";
                        if (st.block == null) st.block = "";
                    }
                    out.add(m);
                }
            });
        } catch (IOException e) {
            Storage.LOG.error("Не удалось прочитать записи", e);
        }
        out.sort(Comparator.comparing((Macro m) -> m.created == null ? "" : m.created).reversed());
        return out;
    }

    public static void save(Macro m) {
        Storage.write(dir().resolve(Storage.fileSafe(m.name) + ".json"), m);
    }

    public static void delete(Macro m) {
        try {
            Files.deleteIfExists(dir().resolve(Storage.fileSafe(m.name) + ".json"));
        } catch (IOException e) {
            Storage.LOG.error("Не удалось удалить запись", e);
        }
    }

    public static void rename(Macro m, String newName) {
        delete(m);
        m.name = newName;
        save(m);
    }
}

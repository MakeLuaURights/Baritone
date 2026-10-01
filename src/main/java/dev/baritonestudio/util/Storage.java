package dev.baritonestudio.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Папка мода в config/ и общий Gson. */
public final class Storage {
    public static final Logger LOG = LoggerFactory.getLogger("BaritoneStudio");
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Storage() {}

    public static Path dir() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("baritonestudio");
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            LOG.error("Не удалось создать {}", p, e);
        }
        return p;
    }

    public static Path sub(String name) {
        Path p = dir().resolve(name);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            LOG.error("Не удалось создать {}", p, e);
        }
        return p;
    }

    public static <T> T read(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type);
        } catch (Exception e) {
            LOG.error("Не удалось прочитать {}", file, e);
            return null;
        }
    }

    public static void write(Path file, Object data) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.error("Не удалось записать {}", file, e);
        }
    }

    /** Безопасное имя файла из названия. */
    public static String fileSafe(String name) {
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.isEmpty()) s = "macro";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}

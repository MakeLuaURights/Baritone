package dev.baritonestudio.preset;

import com.google.gson.reflect.TypeToken;
import dev.baritonestudio.util.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Встроенные пресеты + пользовательские (config/baritonestudio/presets.json). */
public final class PresetStore {
    private static final List<Preset> BUILTIN = new ArrayList<>();
    private static List<Preset> custom = new ArrayList<>();

    static {
        add("stone", "minecraft:stone", Preset.Type.MINE, 0, 0, false,
                "stone", "cobblestone", "andesite", "diorite", "granite", "deepslate", "cobbled_deepslate", "tuff", "calcite");
        add("wood", "minecraft:oak_log", Preset.Type.MINE, 0, 0, false, "#minecraft:logs");
        add("bricks", "minecraft:bricks", Preset.Type.MINE, 0, 0, false,
                "bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "nether_bricks", "red_nether_bricks", "deepslate_bricks");
        add("coal", "minecraft:coal", Preset.Type.MINE, 0, 0, false, "coal_ore", "deepslate_coal_ore");
        add("iron", "minecraft:iron_ingot", Preset.Type.MINE, 0, 0, false, "iron_ore", "deepslate_iron_ore");
        add("copper", "minecraft:copper_ingot", Preset.Type.MINE, 0, 0, false, "copper_ore", "deepslate_copper_ore");
        add("gold", "minecraft:gold_ingot", Preset.Type.MINE, 0, 0, false, "gold_ore", "deepslate_gold_ore", "nether_gold_ore");
        add("redstone", "minecraft:redstone", Preset.Type.MINE, 0, 0, false, "redstone_ore", "deepslate_redstone_ore");
        add("lapis", "minecraft:lapis_lazuli", Preset.Type.MINE, 0, 0, false, "lapis_ore", "deepslate_lapis_ore");
        add("diamond", "minecraft:diamond", Preset.Type.MINE, 0, 0, false, "diamond_ore", "deepslate_diamond_ore");
        add("emerald", "minecraft:emerald", Preset.Type.MINE, 0, 0, false, "emerald_ore", "deepslate_emerald_ore");
        add("quartz", "minecraft:quartz", Preset.Type.MINE, 0, 0, false, "nether_quartz_ore");
        add("debris", "minecraft:netherite_scrap", Preset.Type.MINE, 0, 0, false, "ancient_debris");
        add("dirt", "minecraft:dirt", Preset.Type.MINE, 0, 0, false, "dirt", "grass_block", "coarse_dirt", "podzol", "mycelium", "rooted_dirt");
        add("sand", "minecraft:sand", Preset.Type.MINE, 0, 0, false, "sand", "red_sand", "gravel", "clay");
        add("crops", "minecraft:wheat", Preset.Type.MINE, 0, 0, true,
                "wheat[age=7]", "carrots[age=7]", "potatoes[age=7]", "beetroots[age=3]", "nether_wart[age=3]");
        add("pumpkins", "minecraft:pumpkin", Preset.Type.MINE, 0, 0, false, "pumpkin", "melon", "sugar_cane", "cactus", "bamboo");
        add("tunnel", "minecraft:iron_pickaxe", Preset.Type.TUNNEL, 30, 0, false);
        add("minelevel", "minecraft:diamond_pickaxe", Preset.Type.LEVEL, -58, 0, false);
        add("collect", "minecraft:hopper", Preset.Type.COLLECT, 0, 16, false);
    }

    private PresetStore() {}

    private static void add(String id, String icon, Preset.Type type, int count, int radius, boolean replant, String... blocks) {
        Preset p = new Preset();
        p.id = id;
        p.icon = icon;
        p.type = type;
        p.count = count;
        p.radius = radius;
        p.replant = replant;
        p.builtin = true;
        for (String b : blocks) p.blocks.add(b);
        BUILTIN.add(p);
    }

    private static Path file() {
        return Storage.dir().resolve("presets.json");
    }

    public static void load() {
        custom = new ArrayList<>();
        if (!Files.isRegularFile(file())) return;
        try (var r = Files.newBufferedReader(file())) {
            List<Preset> l = Storage.GSON.fromJson(r, new TypeToken<List<Preset>>() {}.getType());
            if (l != null) custom = new ArrayList<>(l);
        } catch (Exception e) {
            Storage.LOG.error("Не удалось прочитать presets.json", e);
        }
    }

    public static void save() {
        Storage.write(file(), custom);
    }

    public static List<Preset> builtin() {
        return BUILTIN;
    }

    public static List<Preset> custom() {
        return custom;
    }

    public static List<Preset> all() {
        List<Preset> l = new ArrayList<>(BUILTIN);
        l.addAll(custom);
        return l;
    }

    public static Preset find(String id) {
        for (Preset p : all()) if (p.id.equals(id)) return p;
        return null;
    }

    public static void upsert(Preset p) {
        if (p.id == null || p.id.isEmpty()) p.id = "custom_" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < custom.size(); i++) {
            if (custom.get(i).id.equals(p.id)) {
                custom.set(i, p);
                save();
                return;
            }
        }
        custom.add(p);
        save();
    }

    public static void delete(Preset p) {
        custom.removeIf(c -> c.id.equals(p.id));
        save();
    }
}

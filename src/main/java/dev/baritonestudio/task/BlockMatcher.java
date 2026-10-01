package dev.baritonestudio.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/**
 * Условие на блок из текстового списка. Элементы:
 * <ul>
 *   <li>{@code stone} или {@code minecraft:stone} – конкретный блок;</li>
 *   <li>{@code #minecraft:logs} – тег блоков;</li>
 *   <li>{@code wheat[age=7]} – блок с проверкой свойств состояния.</li>
 * </ul>
 */
public final class BlockMatcher {
    private record Entry(Block block, TagKey<Block> tag, Map<String, String> props) {}

    private final List<Entry> entries = new ArrayList<>();
    public final List<String> invalid = new ArrayList<>();
    private boolean all;

    /** Любой блок, который можно сломать (для очистки области). */
    public static BlockMatcher everything() {
        BlockMatcher m = new BlockMatcher();
        m.all = true;
        return m;
    }

    public static BlockMatcher parse(List<String> specs) {
        BlockMatcher m = new BlockMatcher();
        for (String raw : specs) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            Map<String, String> props = new HashMap<>();
            int br = s.indexOf('[');
            if (br >= 0 && s.endsWith("]")) {
                for (String kv : s.substring(br + 1, s.length() - 1).split(",")) {
                    String[] p = kv.split("=", 2);
                    if (p.length == 2) props.put(p[0].trim(), p[1].trim());
                }
                s = s.substring(0, br);
            }
            boolean tag = s.startsWith("#");
            if (tag) s = s.substring(1);
            Identifier id = s.contains(":") ? Identifier.tryParse(s) : Identifier.tryParse("minecraft:" + s);
            if (id == null) {
                m.invalid.add(raw);
                continue;
            }
            if (tag) {
                m.entries.add(new Entry(null, TagKey.of(RegistryKeys.BLOCK, id), props));
            } else {
                Block b = Registries.BLOCK.getOptionalValue(id).orElse(null);
                if (b == null) m.invalid.add(raw);
                else m.entries.add(new Entry(b, null, props));
            }
        }
        return m;
    }

    public boolean isEmpty() {
        return entries.isEmpty() && !all;
    }

    public boolean test(BlockState st) {
        if (st.isAir()) return false;
        if (all) return !st.isLiquid() && st.getBlock().getHardness() >= 0;
        for (Entry e : entries) {
            boolean hit = e.block != null ? st.isOf(e.block) : st.isIn(e.tag);
            if (hit && propsMatch(st, e.props)) return true;
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean propsMatch(BlockState st, Map<String, String> props) {
        if (props.isEmpty()) return true;
        for (Map.Entry<String, String> want : props.entrySet()) {
            boolean ok = false;
            for (Property p : st.getProperties()) {
                if (p.getName().equals(want.getKey())) {
                    ok = p.name(st.get(p)).equals(want.getValue());
                    break;
                }
            }
            if (!ok) return false;
        }
        return true;
    }
}

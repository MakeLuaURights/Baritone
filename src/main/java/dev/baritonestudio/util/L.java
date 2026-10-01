package dev.baritonestudio.util;

import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;

/** Короткий доступ к переводам мода (assets/baritonestudio/lang). */
public final class L {
    private L() {}

    public static String t(String key, Object... args) {
        return I18n.translate("baritonestudio." + key, args);
    }

    public static Text text(String key, Object... args) {
        return Text.translatable("baritonestudio." + key, args);
    }

    public static boolean has(String key) {
        return I18n.hasTranslation("baritonestudio." + key);
    }
}

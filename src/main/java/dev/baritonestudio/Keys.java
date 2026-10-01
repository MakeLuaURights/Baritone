package dev.baritonestudio;

import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Все клавиши мода. Видны в «Управление» и перенастраиваются также в меню мода. */
public final class Keys {
    public static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("baritonestudio", "main"));

    /** Открыть меню — по умолчанию B (Baritone). */
    public static final KeyBinding OPEN = reg("open", GLFW.GLFW_KEY_B);
    public static final KeyBinding STOP = reg("stop", GLFW.GLFW_KEY_N);
    public static final KeyBinding RECORD = reg("record", GLFW.GLFW_KEY_V);
    public static final KeyBinding TARGET_ADD = reg("target_add", GLFW.GLFW_KEY_ENTER);
    public static final KeyBinding TARGET_ROTATE = reg("target_rotate", GLFW.GLFW_KEY_R);
    public static final KeyBinding TARGET_UNDO = reg("target_undo", GLFW.GLFW_KEY_BACKSPACE);

    public static final List<KeyBinding> ALL = List.of(OPEN, STOP, RECORD, TARGET_ADD, TARGET_ROTATE, TARGET_UNDO);

    private Keys() {}

    private static KeyBinding reg(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baritonestudio." + name, InputUtil.Type.KEYSYM, key, CATEGORY));
    }

    /** Нужен только чтобы класс загрузился и клавиши зарегистрировались. */
    public static void init() {}
}

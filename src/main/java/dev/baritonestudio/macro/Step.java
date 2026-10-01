package dev.baritonestudio.macro;

/**
 * Один записанный шаг. Все координаты относительные: от «опорного блока»
 * (блока первого действия записи). Поэтому запись можно повторить в любом месте.
 */
public class Step {
    public enum Type { MOVE, BREAK, USE, SIGN }

    public Type type = Type.MOVE;
    public int x, y, z;

    // ---- USE (ПКМ по блоку: поставить блок, открыть дверь, нажать кнопку...)
    /** Грань, по которой кликнули (Direction.getIndex()). */
    public int side;
    /** Точка клика внутри блока, 0..1. */
    public double hx = 0.5, hy = 0.5, hz = 0.5;
    /** Идентификатор предмета в руке ("minecraft:air" – пустая рука). */
    public String item = "minecraft:air";
    public boolean sneak;
    /** Взгляд игрока в момент клика (нужен для ориентации лестниц, табличек и т.п.). */
    public float yaw, pitch;
    /** Этот клик поставил блок. */
    public boolean placed;
    public int px, py, pz;

    // ---- BREAK / USE
    /** Идентификатор блока: что сломали или что поставили. */
    public String block = "";

    // ---- SIGN
    public boolean front = true;
    public String[] lines = {"", "", "", ""};

    public static Step move(int x, int y, int z) {
        Step s = new Step();
        s.type = Type.MOVE;
        s.x = x;
        s.y = y;
        s.z = z;
        return s;
    }

    public Step copy() {
        Step s = new Step();
        s.type = type;
        s.x = x;
        s.y = y;
        s.z = z;
        s.side = side;
        s.hx = hx;
        s.hy = hy;
        s.hz = hz;
        s.item = item;
        s.sneak = sneak;
        s.yaw = yaw;
        s.pitch = pitch;
        s.placed = placed;
        s.px = px;
        s.py = py;
        s.pz = pz;
        s.block = block;
        s.front = front;
        s.lines = lines == null ? new String[]{"", "", "", ""} : lines.clone();
        return s;
    }

    public boolean isAction() {
        return type != Type.MOVE;
    }
}

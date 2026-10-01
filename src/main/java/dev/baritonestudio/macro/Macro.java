package dev.baritonestudio.macro;

import java.util.ArrayList;
import java.util.List;

/** Записанная последовательность действий. */
public class Macro {
    public int version = 1;
    public String name = "";
    public String created = "";
    /** Куда смотрел игрок при записи: Direction.getHorizontalQuarterTurns(). */
    public int facing = 0;
    /** Значение {n} в тексте табличек для первой цели и шаг приращения для следующих. */
    public int counterStart = 1;
    public int counterStep = 1;
    public List<Step> steps = new ArrayList<>();

    public int actionCount() {
        int n = 0;
        for (Step s : steps) if (s.isAction()) n++;
        return n;
    }

    public int count(Step.Type t) {
        int n = 0;
        for (Step s : steps) if (s.type == t) n++;
        return n;
    }

    public boolean hasSigns() {
        return count(Step.Type.SIGN) > 0;
    }

    public Macro copy() {
        Macro m = new Macro();
        m.version = version;
        m.name = name;
        m.created = created;
        m.facing = facing;
        m.counterStart = counterStart;
        m.counterStep = counterStep;
        for (Step s : steps) m.steps.add(s.copy());
        return m;
    }
}

package dev.baritonestudio.macro;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Перенос записи в мир: смещение к опорному блоку и поворот вокруг вертикальной оси. */
public final class Transform {
    public final BlockPos ref;
    public final int rot;

    public Transform(Target t) {
        this(t.ref(), t.rot());
    }

    public Transform(BlockPos ref, int rot) {
        this.ref = ref;
        this.rot = ((rot % 4) + 4) % 4;
    }

    /** Поворот пары (x,z) на rot четвертей по часовой стрелке (вид сверху): (x,z) -> (-z,x). */
    private int rx(int x, int z) {
        return switch (rot) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    private int rz(int x, int z) {
        return switch (rot) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }

    public BlockPos pos(int x, int y, int z) {
        return new BlockPos(ref.getX() + rx(x, z), ref.getY() + y, ref.getZ() + rz(x, z));
    }

    public BlockPos pos(Step s) {
        return pos(s.x, s.y, s.z);
    }

    public BlockPos placedPos(Step s) {
        return pos(s.px, s.py, s.pz);
    }

    public Direction dir(int index) {
        Direction d = Direction.byIndex(index);
        if (d.getAxis().isVertical()) return d;
        return Direction.fromHorizontalQuarterTurns(d.getHorizontalQuarterTurns() + rot);
    }

    /** Точка клика в мировых координатах. */
    public Vec3d hit(Step s) {
        BlockPos p = pos(s);
        double ox = s.hx - 0.5, oz = s.hz - 0.5;
        double wx, wz;
        switch (rot) {
            case 1 -> { wx = -oz; wz = ox; }
            case 2 -> { wx = -ox; wz = -oz; }
            case 3 -> { wx = oz; wz = -ox; }
            default -> { wx = ox; wz = oz; }
        }
        return new Vec3d(p.getX() + 0.5 + wx, p.getY() + s.hy, p.getZ() + 0.5 + wz);
    }

    public float yaw(Step s) {
        return s.yaw + 90f * rot;
    }

    /** Подставить {n} в строках таблички. */
    public static String fill(String line, int n) {
        return line == null ? "" : line.replace("{n}", Integer.toString(n));
    }
}

package dev.baritonestudio.path;

/** Тип перехода между соседними узлами пути. */
public enum Move {
    START,
    FLAT,
    DIAGONAL,
    ASCEND,
    DESCEND,
    DIG_DOWN,
    PILLAR,
    /** Прыжок через пропасть (parkour). */
    PARKOUR,
    CLIMB_UP,
    CLIMB_DOWN
}

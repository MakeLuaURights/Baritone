package dev.baritonestudio.macro;

import net.minecraft.util.math.BlockPos;

/** Место, где нужно повторить запись: опорный блок и поворот (в четвертях оборота по часовой). */
public record Target(BlockPos ref, int rot) {}

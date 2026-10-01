package dev.baritonestudio.path;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Правила проходимости: что можно пройти, на чём можно стоять и что можно сломать. */
public final class Terrain {
    private final ClientWorld world;
    private final BlockPos.Mutable m = new BlockPos.Mutable();
    public final boolean allowBreak;
    public final boolean allowScaffold;
    public final int maxFall;
    public final boolean avoidWater;
    public final boolean avoidLava;

    public Terrain(ClientWorld world, boolean allowBreak, boolean allowScaffold, int maxFall) {
        this(world, allowBreak, allowScaffold, maxFall,
                dev.baritonestudio.config.ModConfig.get().avoidWater, dev.baritonestudio.config.ModConfig.get().avoidLava);
    }

    public Terrain(ClientWorld world, boolean allowBreak, boolean allowScaffold, int maxFall, boolean avoidWater, boolean avoidLava) {
        this.avoidWater = avoidWater;
        this.avoidLava = avoidLava;
        this.world = world;
        this.allowBreak = allowBreak;
        this.allowScaffold = allowScaffold;
        this.maxFall = maxFall;
    }

    public ClientWorld world() {
        return world;
    }

    public boolean loaded(int x, int z) {
        return world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    public boolean inBounds(int y) {
        return y > world.getBottomY() + 1 && y < world.getBottomY() + world.getHeight() - 2;
    }

    public BlockState state(int x, int y, int z) {
        return world.getBlockState(m.set(x, y, z));
    }

    public boolean isLava(BlockState s) {
        return s.getFluidState().isIn(FluidTags.LAVA);
    }

    public boolean isWater(BlockState s) {
        return s.getFluidState().isIn(FluidTags.WATER);
    }

    /** Опасные блоки, которые не имеют коллизии, но вредят или замедляют. */
    private boolean harmful(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW
                || b == Blocks.WITHER_ROSE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS
                || b == Blocks.POINTED_DRIPSTONE || b == Blocks.BUBBLE_COLUMN;
    }

    /** Можно ли находиться в этой клетке без коллизии (воздух, трава, вода, открытая дверь...). */
    public boolean passable(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.isAir()) return true;
        if (isLava(s)) return !avoidLava;
        if (harmful(s)) return false;
        if (avoidWater && isWater(s)) return false;
        return s.getCollisionShape(world, m.set(x, y, z)).isEmpty();
    }

    /** Клетка с водой (пловец). */
    public boolean water(int x, int y, int z) {
        return isWater(state(x, y, z));
    }

    /** Можно ли стоять на блоке (x,y,z) — верхняя грань полная. */
    public boolean solidTop(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.isAir() || isLava(s) || harmful(s)) return false;
        return s.isSideSolidFullSquare(world, m.set(x, y, z), Direction.UP);
    }

    /** Можно ли сломать блок (x,y,z) безопасно. */
    public boolean breakable(int x, int y, int z) {
        if (!allowBreak) return false;
        BlockState s = state(x, y, z);
        if (s.isAir() || s.getFluidState().isIn(FluidTags.LAVA)) return false;
        if (s.getHardness(world, m.set(x, y, z)) < 0) return false;
        if (s.hasBlockEntity()) return false;
        if (isStructure(s)) return false;
        if (dev.baritonestudio.config.ModConfig.avoidBreakingMatcher().test(s)) return false;
        if (s.getBlock() == Blocks.SPAWNER || s.getBlock() == Blocks.TRIAL_SPAWNER) return false;
        // не вскрываем лаву: если рядом лава, то ломать нельзя
        for (Direction d : Direction.values()) {
            BlockState n = state(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ());
            if (isLava(n)) return false;
        }
        return true;
    }

    /** Блоки, похожие на часть постройки: их по пути не ломаем (двери, заборы, лестницы, рельсы...). */
    private static boolean isStructure(BlockState s) {
        return s.isIn(BlockTags.DOORS) || s.isIn(BlockTags.TRAPDOORS) || s.isIn(BlockTags.FENCE_GATES)
                || s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.WALLS) || s.isIn(BlockTags.BEDS)
                || s.isIn(BlockTags.CLIMBABLE) || s.isIn(BlockTags.RAILS) || s.isIn(BlockTags.BUTTONS)
                || s.isIn(BlockTags.PRESSURE_PLATES) || s.isIn(BlockTags.ALL_SIGNS) || s.isIn(BlockTags.BANNERS)
                || s.isIn(BlockTags.CANDLES) || s.isIn(BlockTags.FLOWER_POTS) || s.isIn(BlockTags.CAULDRONS)
                || s.isIn(BlockTags.ANVIL) || s.isIn(BlockTags.SHULKER_BOXES) || s.isIn(BlockTags.CAMPFIRES)
                || s.isIn(BlockTags.ALL_HANGING_SIGNS) || s.isIn(BlockTags.CANDLE_CAKES)
                || s.isOf(Blocks.CRAFTING_TABLE) || s.isOf(Blocks.LANTERN) || s.isOf(Blocks.SOUL_LANTERN)
                || s.isOf(Blocks.GLASS_PANE) || s.isOf(Blocks.IRON_BARS) || s.isOf(Blocks.OBSIDIAN)
                || s.isOf(Blocks.CRYING_OBSIDIAN) || s.isOf(Blocks.RESPAWN_ANCHOR) || s.isOf(Blocks.LODESTONE);
    }

    /** Стоимость освобождения клетки: 0 если проходима, иначе стоимость ломания, либо {@link #INF}. */
    public static final double INF = 1e9;

    public double clearCost(int x, int y, int z) {
        if (passable(x, y, z)) {
            if (isLava(state(x, y, z))) return 40.0;
            return water(x, y, z) ? 1.0 : 0.0;
        }
        if (!breakable(x, y, z)) return INF;
        BlockState s = state(x, y, z);
        double c = 4.0 + Math.min(12.0, s.getHardness(world, m.set(x, y, z)) * 1.5);
        // сыпучий блок над головой – ломаем осторожнее (дороже)
        if (state(x, y + 1, z).getBlock() instanceof FallingBlock) c += 3;
        return c;
    }

    /** Есть ли лава вплотную к блоку: ломать такой блок опасно. */
    public static boolean nextToLava(Terrain t, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            if (t.isLava(t.state(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ()))) return true;
        }
        return false;
    }

    /** Блок без падения, цельный и подходящий как опора. */
    public static boolean scaffoldBlock(BlockState s) {
        Block b = s.getBlock();
        return !(b instanceof FallingBlock) && !s.hasBlockEntity() && s.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN)
                && b != Blocks.TNT && b != Blocks.SPONGE && b != Blocks.WET_SPONGE;
    }
}

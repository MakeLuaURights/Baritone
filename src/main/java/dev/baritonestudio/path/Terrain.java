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
    /** Можно ли бежать (спринт): влияет на стоимость ходов и допустимость прыжков через пропасти. */
    public boolean canSprint = true;
    /** Разрешены ли прыжки через пропасти (parkour). */
    public boolean allowParkour = false;
    /** Позиции враждебных мобов: клетки рядом с ними дороже, чтобы путь огибал их. */
    public java.util.List<net.minecraft.util.math.Vec3d> hostiles = java.util.List.of();

    public void scanHostiles(net.minecraft.client.network.ClientPlayerEntity p) {
        java.util.List<net.minecraft.util.math.Vec3d> l = new java.util.ArrayList<>();
        for (var e : world.getEntitiesByClass(net.minecraft.entity.mob.HostileEntity.class, p.getBoundingBox().expand(40), e -> e.isAlive())) {
            l.add(e.getEntityPos());
            if (l.size() >= 24) break;
        }
        hostiles = l;
    }

    /** Закрытая дверь/калитка, которую можно открыть ПКМ (железные двери – нет). */
    public static boolean openable(BlockState s) {
        if (s.getBlock() == Blocks.IRON_DOOR) return false;
        return s.isIn(BlockTags.WOODEN_DOORS) || s.isIn(BlockTags.FENCE_GATES) || (s.isIn(BlockTags.DOORS) && s.getBlock() != Blocks.IRON_DOOR);
    }

    /** Надбавка за вход в клетку: соседство лавы/кактуса, медленная земля, закрытая дверь, враждебные мобы рядом. */
    public double entryPenalty(int x, int y, int z) {
        double c = 0;
        BlockState below = state(x, y - 1, z);
        Block bb = below.getBlock();
        if (bb == Blocks.SOUL_SAND || bb == Blocks.HONEY_BLOCK || bb == Blocks.SLIME_BLOCK) c += 4;
        if (bb == Blocks.SOUL_SOIL) c += 1;
        for (int i = 0; i < 4; i++) {
            Direction d = Direction.fromHorizontalQuarterTurns(i);
            for (int dy = 0; dy <= 1; dy++) {
                BlockState n = state(x + d.getOffsetX(), y + dy, z + d.getOffsetZ());
                if (isLava(n)) c += 8;
                else if (n.getBlock() == Blocks.CACTUS || n.getBlock() == Blocks.FIRE || n.getBlock() == Blocks.SOUL_FIRE) c += 6;
            }
        }
        if (openable(state(x, y, z))) c += 2;
        for (var h : hostiles) {
            double dx = h.x - (x + 0.5), dz = h.z - (z + 0.5), dy = h.y - y;
            if (dy > -2 && dy < 3) {
                double d2 = dx * dx + dz * dz;
                if (d2 < 16) c += 10 * (1 - Math.sqrt(d2) / 4);
            }
        }
        return c;
    }
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> cache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private boolean useCache;
    private final java.util.HashMap<BlockState, Double> breakCache = new java.util.HashMap<>();

    /** Включить кэш блоков на время одного поиска (мир не должен меняться заметно). */
    public Terrain cached() {
        useCache = true;
        return this;
    }

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
        if (!useCache) return world.getBlockState(m.set(x, y, z));
        long key = BlockPos.asLong(x, y, z);
        BlockState st = cache.get(key);
        if (st == null) {
            st = world.getBlockState(m.set(x, y, z));
            cache.put(key, st);
        }
        return st;
    }

    /** Лестница, лоза, леса и т. п. – по ним можно лазить. */
    public boolean climbable(int x, int y, int z) {
        return state(x, y, z).isIn(BlockTags.CLIMBABLE);
    }

    /** Время ломания блока в тиках лучшим инструментом из инвентаря (как считает Baritone). */
    public double breakTicks(int x, int y, int z) {
        BlockState s = state(x, y, z);
        Double c = breakCache.get(s);
        if (c != null) return c;
        double result;
        var player = net.minecraft.client.MinecraftClient.getInstance().player;
        float hardness = s.getHardness(world, m.set(x, y, z));
        if (hardness < 0) {
            result = Costs.INF;
        } else if (hardness == 0 || (player != null && player.isCreative())) {
            result = 1;
        } else {
            double bestDelta = 1.0 / hardness / (s.isToolRequired() ? 100.0 : 30.0); // голая рука
            if (player != null) {
                var inv = player.getInventory();
                for (int i = 0; i < 36; i++) {
                    var st = inv.getStack(i);
                    if (st.isEmpty()) continue;
                    double speed = st.getMiningSpeedMultiplier(s);
                    double div = (!s.isToolRequired() || st.isSuitableFor(s)) ? 30.0 : 100.0;
                    bestDelta = Math.max(bestDelta, speed / hardness / div);
                }
            }
            result = Math.max(1, Math.ceil(1.0 / bestDelta));
        }
        breakCache.put(s, result);
        return result;
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
        if (s.isIn(BlockTags.CLIMBABLE)) return true;
        if (openable(s)) return true; // откроем по пути
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
        if (s.isAir() || s.isLiquid() || s.getFluidState().isIn(FluidTags.LAVA)) return false;
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
    public static final double INF = Costs.INF;

    public double clearCost(int x, int y, int z) {
        if (passable(x, y, z)) {
            if (isLava(state(x, y, z))) return 200.0;
            return 0.0;
        }
        if (!breakable(x, y, z)) return INF;
        double c = breakTicks(x, y, z) + Costs.BREAK_PENALTY;
        // сыпучий блок над головой – ломаем осторожнее (дороже)
        if (state(x, y + 1, z).getBlock() instanceof FallingBlock) c += 12;
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

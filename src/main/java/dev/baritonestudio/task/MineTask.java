package dev.baritonestudio.task;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.path.Terrain;
import dev.baritonestudio.util.L;
import dev.baritonestudio.util.Storage;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Добыча блоков по условию: найти ближайший, дойти, сломать, подобрать дроп, повторить. */
public final class MineTask extends Task {
    private enum Phase { SCAN, NAV, MINE, SETTLE, REPLANT, COLLECT }

    private final String title;
    private final BlockMatcher matcher;
    private final int goalCount;
    private final int radius;
    private final boolean replant;
    private final Navigator nav = new Navigator();
    private final DropCollector collector = new DropCollector(nav, 10);
    private final Set<BlockPos> bad = new HashSet<>();

    private Phase phase = Phase.SCAN;
    private BlockPos target;
    private int mined;
    private int ticks;
    private int idle;
    private boolean finalSweep, finalSweepDone;
    private BlockPos lastMined;
    private String status = "";

    public MineTask(String title, BlockMatcher matcher, int count, int radius, boolean replant) {
        this.title = title;
        this.matcher = matcher;
        this.goalCount = Math.max(0, count);
        this.radius = radius > 0 ? radius : ModConfig.get().searchRadius;
        this.replant = replant;
    }

    @Override
    public String name() {
        return title;
    }

    @Override
    public String status() {
        String s = goalCount > 0 ? L.t("status.mined_of", mined, goalCount) : L.t("status.mined", mined);
        return s + " • " + status;
    }

    @Override
    public double progress() {
        return goalCount > 0 ? Math.min(1.0, (double) mined / goalCount) : -1;
    }

    @Override
    public Navigator navigator() {
        return nav;
    }

    @Override
    public void start(Actor a) {
        nav.breakMode = Navigator.BreakMode.ALWAYS;
        if (matcher.isEmpty()) fail(L.t("status.no_blocks_set"));
    }

    @Override
    public void stop(Actor a) {
        nav.clear();
    }

    @Override
    public void tick(Actor a) {
        ModConfig cfg = ModConfig.get();
        if (cfg.stopOnFullInventory && a.inventoryFull() && phase != Phase.COLLECT) {
            finish(L.t("status.inventory_full", mined));
            return;
        }
        switch (phase) {
            case SCAN -> scan(a, cfg);
            case NAV -> navigate(a);
            case MINE -> mine(a);
            case SETTLE -> {
                if (--ticks <= 0) {
                    phase = replant && lastMined != null ? Phase.REPLANT : (cfg.collectDrops ? Phase.COLLECT : Phase.SCAN);
                    collector.reset();
                    ticks = 0;
                    idle = 0;
                }
            }
            case REPLANT -> replant(a, cfg);
            case COLLECT -> {
                status = L.t("status.collecting");
                if (!collector.tick(a)) {
                    idle = 0;
                } else {
                    // между блоками подбираем только то, что уже выпало; в конце ждём появления дропа (лаг/пинг)
                    int limit = finalSweep ? 24 + a.latencyTicks() : 0;
                    if (++idle > limit) phase = Phase.SCAN;
                }
            }
        }
    }

    /** Перед завершением: один раз ждём и подбираем оставшийся дроп. Возвращает true, если пошли собирать. */
    private boolean startFinalSweep(ModConfig cfg) {
        if (finalSweepDone || !cfg.collectDrops || mined == 0) return false;
        finalSweepDone = true;
        finalSweep = true;
        phase = Phase.COLLECT;
        idle = 0;
        collector.reset();
        return true;
    }

    private void scan(Actor a, ModConfig cfg) {
        if (goalCount > 0 && mined >= goalCount) {
            if (startFinalSweep(cfg)) return;
            finish(L.t("status.mine_done", mined));
            return;
        }
        Terrain t = new Terrain(a.world(), true, false, cfg.maxFall);
        BlockPos found = BlockScanner.nearest(a.world(), a.player().getBlockPos(), radius, matcher, bad, t);
        if (found == null) {
            if (mined == 0) {
                fail(L.t("status.nothing_found", radius));
            } else {
                if (startFinalSweep(cfg)) return;
                finish(L.t("status.no_more", mined));
            }
            return;
        }
        target = found;
        Storage.LOG.info("MineTask[{}]: цель {} (добыто {}, исключено {})", title, found.toShortString(), mined, bad.size());
        status = L.t("status.going_to", found.getX(), found.getY(), found.getZ());
        nav.setGoal(new Goal.Adjacent(found));
        phase = Phase.NAV;
        ticks = 0;
    }

    private void navigate(Actor a) {
        BlockState st = a.world().getBlockState(target);
        if (!matcher.test(st)) {
            phase = Phase.SCAN;
            return;
        }
        // уже в зоне досягаемости и видим блок – копаем сразу
        if (a.inReach(target)) {
            BlockHitResult hit = a.sight(target);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
                nav.clear();
                phase = Phase.MINE;
                ticks = 0;
                return;
            }
        }
        switch (nav.tick(a)) {
            case ARRIVED -> {
                phase = Phase.MINE;
                ticks = 0;
            }
            case FAILED -> {
                Storage.LOG.info("MineTask[{}]: путь к {} не удался: {}", title, target.toShortString(), nav.error);
                bad.add(target);
                if (bad.size() > 60) fail(L.t("status.too_many_failures"));
                phase = Phase.SCAN;
            }
            default -> {}
        }
    }

    private void mine(Actor a) {
        BlockState st = a.world().getBlockState(target);
        if (!matcher.test(st)) {
            // блок исчез: либо сломали, либо кто-то другой
            onMined(a, st);
            return;
        }
        status = L.t("status.mining", st.getBlock().getName().getString());
        Actor.MineResult r = a.mine(target);
        if (r == Actor.MineResult.FAIL) {
            // не дотянулись – пробуем подойти
            nav.setGoal(new Goal.Adjacent(target));
            phase = Phase.NAV;
            if (++ticks > 6) {
                bad.add(target);
                phase = Phase.SCAN;
            }
            return;
        }
        if (++ticks > 600) {
            bad.add(target);
            phase = Phase.SCAN;
        }
    }

    private void onMined(Actor a, BlockState nowState) {
        mined++;
        lastMined = target;
        phase = Phase.SETTLE;
        ticks = 2;
    }

    private void replant(Actor a, ModConfig cfg) {
        phase = cfg.collectDrops ? Phase.COLLECT : Phase.SCAN;
        collector.reset();
        idle = 0;
        BlockPos farm = lastMined.down();
        BlockState soil = a.world().getBlockState(farm);
        Item seed = soil.isOf(Blocks.SOUL_SAND) ? Items.NETHER_WART : null;
        boolean farmland = soil.isOf(Blocks.FARMLAND);
        if (!farmland && seed == null) return;
        // пробуем семена: пшеница, морковь, картофель, свёкла
        Item[] seeds = seed != null ? new Item[]{seed} : new Item[]{Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS};
        for (Item s : seeds) {
            if (a.hasItem(st -> st.isOf(s))) {
                a.selectItem(st -> st.isOf(s));
                if (a.world().getBlockState(lastMined).isAir() || a.world().getBlockState(lastMined).isReplaceable()) {
                    a.placeAgainst(farm, Direction.UP);
                }
                return;
            }
        }
    }
}

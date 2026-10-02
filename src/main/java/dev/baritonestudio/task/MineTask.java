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
    private final DropCollector collector = new DropCollector(nav, 9);
    private final Set<BlockPos> bad = new HashSet<>();

    private Phase phase = Phase.SCAN;
    private BlockPos target;
    private int mined;
    private int ticks;
    private int idle;
    private boolean finalSweep, finalSweepDone, expectDrop;
    private int retries;
    private java.util.List<BlockPos> cands = java.util.List.of();
    private float lastHardness;
    private double styleDist;
    private boolean lastWasLog;
    private BlockPos lastMined;
    private String status = "";
    /** Анти-xray: сервер подсовывает фейковые руды; после двух разоблачений ищем только открытые блоки. */
    private static boolean antiXray;
    private int fakes;
    private boolean lastWasOre, checkDrop;

    public static boolean antiXray() {
        return antiXray;
    }

    public static void resetAntiXray() {
        antiXray = false;
    }

    private static boolean isOre(BlockState st) {
        String id = net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).getPath();
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    private static boolean isFiller(net.minecraft.item.ItemStack s) {
        return s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE) || s.isOf(Items.STONE) || s.isOf(Items.DIRT)
                || s.isOf(Items.NETHERRACK) || s.isOf(Items.END_STONE) || s.isOf(Items.TUFF) || s.isOf(Items.DEEPSLATE)
                || s.isOf(Items.GRANITE) || s.isOf(Items.DIORITE) || s.isOf(Items.ANDESITE) || s.isOf(Items.BLACKSTONE)
                || s.isOf(Items.GRAVEL) || s.isOf(Items.SAND);
    }

    private void noteFake(Actor a, BlockPos pos) {
        fakes++;
        bad.add(pos);
        Storage.LOG.info("MineTask[{}]: фейк-руда в {} (всего {})", title, pos.toShortString(), fakes);
        if (fakes >= 2 && !antiXray) {
            antiXray = true;
            a.player().sendMessage(net.minecraft.text.Text.literal("[Baritone Studio] " + L.t("status.antixray"))
                    .formatted(net.minecraft.util.Formatting.GOLD), false);
        }
    }

    private java.util.function.Predicate<BlockPos> region;
    private boolean topDown;

    /** Добыча внутри заданной области (очистка): сверху вниз, любым ломаемым блоком. */
    public MineTask(String title, BlockMatcher matcher, int radius, java.util.function.Predicate<BlockPos> region, boolean topDown) {
        this(title, matcher, 0, radius, false);
        this.region = region;
        this.topDown = topDown;
    }

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
        a.avoidScaffold = matcher;
        if (matcher.isEmpty()) fail(L.t("status.no_blocks_set"));
    }

    @Override
    public void stop(Actor a) {
        nav.clear();
        a.avoidScaffold = null;
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
                    if (checkDrop) verifyOreDrop(a);
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
                    int limit = finalSweep ? 24 + a.latencyTicks() : (expectDrop ? 4 + a.latencyTicks() : 0);
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
        bad.addAll(a.placedScaffold); // собственные опоры – не цели
        cands = BlockScanner.nearestN(a.world(), a.player().getBlockPos(), radius, matcher, bad, t, region, topDown,
                (cfg.legitMine || antiXray) && region == null, 8);
        if (cands.isEmpty() && !bad.isEmpty() && retries < 2) {
            // «чёрный список» мог набраться из-за временных сбоев – даём второй шанс
            retries++;
            Storage.LOG.info("MineTask[{}]: повторная попытка, исключённых целей: {}", title, bad.size());
            bad.clear();
            return;
        }
        if (cands.isEmpty()) {
            if (mined == 0) {
                fail(L.t("status.nothing_found", radius));
            } else {
                if (startFinalSweep(cfg)) return;
                finish(L.t("status.no_more", mined));
            }
            return;
        }
        target = cands.get(0);
        Storage.LOG.info("MineTask[{}]: цель {} из {} кандидатов (добыто {}, исключено {})", title, target.toShortString(), cands.size(), mined, bad.size());
        status = L.t("status.going_to", target.getX(), target.getY(), target.getZ());
        // одним поиском выбираем самую дешёвую по длине пути цель среди ближайших кандидатов
        java.util.List<dev.baritonestudio.path.Goal> goals = new java.util.ArrayList<>();
        // дерево + записанный почерк: подходим на привычную игроку дистанцию удара, а не вплотную
        styleDist = 0;
        if (a.world().getBlockState(target).isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
            var prof = dev.baritonestudio.human.HumanStyle.forLog(target, a.world().getTime());
            if (prof != null) styleDist = Math.max(1.8, Math.min(cfg.reach - 0.4, prof.hitDistance));
        }
        for (BlockPos c : cands) goals.add(styleDist > 0 ? new Goal.Within(c, styleDist) : new Goal.Adjacent(c));
        nav.setGoal(goals.size() == 1 ? goals.get(0) : new Goal.Any(goals));
        phase = Phase.NAV;
        ticks = 0;
    }

    /** Выбирает из кандидатов тот блок, к которому мы реально подошли (в зоне досягаемости и видимости). */
    private BlockPos reachableCandidate(Actor a, boolean requireAdjacent) {
        BlockPos me = a.player().getBlockPos();
        for (BlockPos c : cands) {
            if (!matcher.test(a.world().getBlockState(c))) continue;
            if (requireAdjacent) {
                Goal g = styleDist > 0 ? new Goal.Within(c, styleDist) : new Goal.Adjacent(c);
                if (g.isEnd(me.getX(), me.getY(), me.getZ())) return c;
            }
            if (!requireAdjacent && (styleDist > 0 ? a.eyeDistance(c) <= styleDist + 0.2 : a.inReach(c))) {
                BlockHitResult hit = a.sight(c);
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(c)) return c;
            }
        }
        return null;
    }

    private void navigate(Actor a) {
        boolean anyValid = false;
        for (BlockPos c : cands) if (matcher.test(a.world().getBlockState(c))) anyValid = true;
        if (!anyValid) {
            phase = Phase.SCAN;
            return;
        }
        // уже в зоне досягаемости и видим блок – копаем сразу
        BlockPos near = reachableCandidate(a, false);
        if (near != null) {
            target = near;
            nav.clear();
            phase = Phase.MINE;
            ticks = 0;
            return;
        }
        switch (nav.tick(a)) {
            case ARRIVED -> {
                BlockPos c = reachableCandidate(a, true);
                if (c == null) c = reachableCandidate(a, false);
                if (c == null) {
                    phase = Phase.SCAN;
                    return;
                }
                target = c;
                phase = Phase.MINE;
                ticks = 0;
            }
            case FAILED -> {
                Storage.LOG.info("MineTask[{}]: путь к {} не удался: {}", title, target.toShortString(), nav.error);
                // «не нашли путь ни к одному»: исключаем все кандидаты, иначе – только ближайший
                if (nav.error.contains("не найден")) bad.addAll(cands);
                else bad.add(target);
                if (bad.size() > 80) fail(L.t("status.too_many_failures"));
                phase = Phase.SCAN;
            }
            default -> {}
        }
    }

    private void mine(Actor a) {
        BlockState st = a.world().getBlockState(target);
        if (!matcher.test(st)) {
            // блок исчез: либо сломали, либо кто-то другой
            if (!st.isAir() && !st.isReplaceable() && st.getFluidState().isEmpty()) {
                // вместо руды сервер вернул обычный блок – это была фейк-руда (анти-xray)
                noteFake(a, target);
                phase = Phase.SCAN;
                return;
            }
            onMined(a, st);
            return;
        }
        status = L.t("status.mining", st.getBlock().getName().getString());
        lastHardness = st.getHardness(a.world(), target);
        lastWasLog = st.isIn(net.minecraft.registry.tag.BlockTags.LOGS);
        lastWasOre = isOre(st);
        Actor.MineResult r = a.mine(target);
        if (r == Actor.MineResult.FAIL) {
            // не дотянулись – пробуем подойти
            nav.setGoal(new Goal.Adjacent(target));
            phase = Phase.NAV;
            if (++ticks > 6) {
                Storage.LOG.info("MineTask[{}]: не дотянуться до {}", title, target.toShortString());
                bad.add(target);
                phase = Phase.SCAN;
            }
            return;
        }
        if (++ticks > 600) {
            Storage.LOG.info("MineTask[{}]: таймаут ломания {}", title, target.toShortString());
            bad.add(target);
            phase = Phase.SCAN;
        }
    }

    private void onMined(Actor a, BlockState nowState) {
        mined++;
        lastMined = target;
        expectDrop = lastHardness > 0f; // у мгновенно ломающихся (трава) дропа обычно нет
        checkDrop = lastWasOre && expectDrop;
        phase = Phase.SETTLE;
        var styled = lastWasLog ? dev.baritonestudio.human.HumanStyle.current(a.world().getTime()) : null;
        ticks = styled != null ? Math.max(1, styled.sampleThink(dev.baritonestudio.human.HumanStyle.rnd()))
                : Human.get().jitter(2) + (Human.get().on() ? Human.get().thinkPause() : 0);
        if (checkDrop) ticks += 3 + a.latencyTicks();
    }

    /** Руда, из которой выпал только булыжник/камень – подделка: не считаем её добытой. */
    private void verifyOreDrop(Actor a) {
        checkDrop = false;
        var box = new net.minecraft.util.math.Box(lastMined).expand(2.5);
        var items = a.world().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, box, e -> true);
        if (items.isEmpty()) return;
        for (var e : items) if (!isFiller(e.getStack())) return;
        mined = Math.max(0, mined - 1);
        noteFake(a, lastMined);
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

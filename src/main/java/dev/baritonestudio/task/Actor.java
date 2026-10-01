package dev.baritonestudio.task;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.path.Terrain;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * «Руки и ноги» бота: нажатие клавиш движения, поворот головы, выбор предмета,
 * ломание и установка блоков. Все задачи работают только через него.
 */
public final class Actor {
    public enum MineResult { WORKING, DONE, FAIL }

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private boolean fwd, sprint, jump, sneak, use, left, right, anyKeyHeld;
    private BlockPos humanTarget;
    private int humanWait;
    private int toolWait;

    public ClientPlayerEntity player() {
        return mc.player;
    }

    public ClientWorld world() {
        return mc.world;
    }

    // ---------------------------------------------------------------- клавиши

    public void forward(boolean v) { fwd = v; }
    public void sprint(boolean v) { sprint = v; }
    public void jump(boolean v) { jump = v; }
    public void sneak(boolean v) { sneak = v; }
    public void use(boolean v) { use = v; }
    public void left(boolean v) { left = v; }
    public void right(boolean v) { right = v; }

    /** Выставить сохранённые флаги в клавиши игры. Вызывается раз в тик после логики задач. */
    public void apply() {
        GameOptions o = mc.options;
        boolean any = fwd || sprint || jump || sneak || use || left || right;
        if (any) {
            o.forwardKey.setPressed(fwd);
            o.sprintKey.setPressed(sprint);
            o.jumpKey.setPressed(jump);
            o.sneakKey.setPressed(sneak);
            o.useKey.setPressed(use);
            o.leftKey.setPressed(left);
            o.rightKey.setPressed(right);
            anyKeyHeld = true;
        } else if (anyKeyHeld) {
            releaseKeys();
        }
        fwd = sprint = jump = sneak = use = left = right = false;
    }

    /** Отпустить все клавиши, которые мы нажимали, и вернуть физическое состояние клавиатуры. */
    public void releaseKeys() {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.sprintKey.setPressed(false);
        o.jumpKey.setPressed(false);
        o.sneakKey.setPressed(false);
        o.useKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        anyKeyHeld = false;
        fwd = sprint = jump = sneak = use = left = right = false;
        if (mc.currentScreen == null) KeyBinding.updatePressedStates();
    }

    // ---------------------------------------------------------------- взгляд

    /** Плавно повернуться к точке. Возвращает максимальное оставшееся отклонение в градусах. */
    public float lookAt(Vec3d target) {
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(dy, hd) * 180.0 / Math.PI);
        return lookAngles(yaw, MathHelper.clamp(pitch, -90f, 90f));
    }

    /** Угол поворота (yaw) в сторону точки. */
    public float yawTo(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        return (float) (MathHelper.atan2(target.z - eye.z, target.x - eye.x) * 180.0 / Math.PI) - 90.0f;
    }

    public float lookAngles(float yaw, float pitch) {
        ClientPlayerEntity p = mc.player;
        float speed = ModConfig.get().rotateSpeed;
        Human h = Human.get();
        if (h.on()) return humanLook(p, yaw, pitch, speed, h);
        float dyaw = MathHelper.wrapDegrees(yaw - p.getYaw());
        float dpitch = pitch - p.getPitch();
        if (speed <= 0f) {
            p.setYaw(p.getYaw() + dyaw);
            p.setPitch(pitch);
            return 0f;
        }
        float sy = MathHelper.clamp(dyaw, -speed, speed);
        float sp = MathHelper.clamp(dpitch, -speed, speed);
        p.setYaw(p.getYaw() + sy);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp, -90f, 90f));
        return Math.max(Math.abs(dyaw - sy), Math.abs(dpitch - sp));
    }

    /** Плавное наведение «как мышью»: замедление к цели, шум и шаги, кратные чувствительности мыши. */
    private float humanLook(ClientPlayerEntity p, float yaw, float pitch, float speed, Human h) {
        h.updateNoise();
        float dyaw = MathHelper.wrapDegrees(yaw + h.noiseYaw() - p.getYaw());
        float dpitch = pitch + h.noisePitch() - p.getPitch();
        float max = (speed <= 0f ? 90f : speed) * (0.55f + h.rnd().nextFloat() * 0.5f);
        float gain = h.turnGain();
        float sy = MathHelper.clamp(dyaw * gain, -max, max);
        float sp = MathHelper.clamp(dpitch * gain, -max, max);
        float grid = h.mouseStep();
        sy = quantize(sy, grid, dyaw);
        sp = quantize(sp, grid, dpitch);
        p.setYaw(p.getYaw() + sy);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp, -90f, 90f));
        return Math.max(Math.abs(dyaw - sy), Math.abs(dpitch - sp));
    }

    private static float quantize(float step, float grid, float remaining) {
        float q = Math.round(step / grid) * grid;
        if (q == 0f && Math.abs(remaining) > grid * 1.5f) q = Math.signum(remaining) * grid;
        if (Math.abs(remaining) <= grid * 0.5f) return 0f;
        return q;
    }

    // ---------------------------------------------------------------- инвентарь

    private boolean isDepleted(ItemStack s) {
        return s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 2;
    }

    /** Лучший инструмент для блока. Переносит его в текущий слот хотбара при необходимости. */
    public void selectBestTool(BlockState state) {
        PlayerInventory inv = mc.player.getInventory();
        int cur = inv.getSelectedSlot();
        int best = cur;
        float bestScore = score(inv.getStack(cur), state);
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || isDepleted(s)) continue;
            float sc = score(s, state);
            if (sc > bestScore + 0.01f) {
                bestScore = sc;
                best = i;
            }
        }
        if (best != cur) {
            moveToSelected(best);
            if (Human.get().on()) toolWait = 2 + Human.get().rnd().nextInt(5);
        }
    }

    private float score(ItemStack s, BlockState state) {
        if (s.isEmpty()) return 1.0f;
        if (isDepleted(s)) return -1f;
        float v = s.getMiningSpeedMultiplier(state);
        if (state.isToolRequired() && !s.isSuitableFor(state)) v *= 0.1f;
        return v;
    }

    /** Взять в руку предмет, подходящий под условие. false если такого нет. */
    public boolean selectItem(Predicate<ItemStack> pred) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && pred.test(s)) {
                if (inv.getSelectedSlot() != i) inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && pred.test(s)) {
                moveToSelected(i);
                return true;
            }
        }
        return false;
    }

    /** Взять в руку пустой слот или предмет, который не поставит блок случайно. */
    public void selectEmptyHand() {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) {
                inv.setSelectedSlot(i);
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!(s.getItem() instanceof BlockItem) && !s.contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                inv.setSelectedSlot(i);
                return;
            }
        }
    }

    public int countItem(Predicate<ItemStack> pred) {
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && pred.test(s)) n += s.getCount();
        }
        return n;
    }

    public boolean hasItem(Predicate<ItemStack> pred) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && pred.test(s)) return true;
        }
        return false;
    }

    /** Позиции блоков-опор, поставленных самим ботом: их не считаем целями добычи. */
    public final java.util.Set<BlockPos> placedScaffold = new java.util.HashSet<>();
    /** Блоки, которые текущая задача добывает: ими нельзя строить столб (иначе бот срубит собственную опору). */
    public BlockMatcher avoidScaffold;

    private static final java.util.Set<net.minecraft.block.Block> CHEAP = java.util.Set.of(
            net.minecraft.block.Blocks.DIRT, net.minecraft.block.Blocks.COBBLESTONE, net.minecraft.block.Blocks.STONE,
            net.minecraft.block.Blocks.NETHERRACK, net.minecraft.block.Blocks.COBBLED_DEEPSLATE, net.minecraft.block.Blocks.DEEPSLATE,
            net.minecraft.block.Blocks.ANDESITE, net.minecraft.block.Blocks.DIORITE, net.minecraft.block.Blocks.GRANITE,
            net.minecraft.block.Blocks.TUFF, net.minecraft.block.Blocks.COARSE_DIRT, net.minecraft.block.Blocks.BLACKSTONE,
            net.minecraft.block.Blocks.BASALT, net.minecraft.block.Blocks.END_STONE, net.minecraft.block.Blocks.SANDSTONE);

    /** Допустим ли предмет как опора: цельный несыпучий блок, не дерево и не то, что сейчас добываем. */
    private boolean scaffoldOk(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        BlockState st = bi.getBlock().getDefaultState();
        if (!Terrain.scaffoldBlock(st)) return false;
        if (st.isIn(net.minecraft.registry.tag.BlockTags.LOGS) || st.isIn(net.minecraft.registry.tag.BlockTags.PLANKS)) return false;
        return avoidScaffold == null || !avoidScaffold.test(st);
    }

    private boolean scaffoldCheap(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && CHEAP.contains(bi.getBlock()) && scaffoldOk(s);
    }

    public boolean hasScaffold() {
        return hasItem(this::scaffoldOk);
    }

    /** Берёт в руку самый дешёвый подходящий блок для столба. */
    public boolean selectScaffold() {
        if (hasItem(this::scaffoldCheap)) return selectItem(this::scaffoldCheap);
        return selectItem(this::scaffoldOk);
    }

    /** Задержка до сервера в тиках — чтобы не считать ответ сервера опоздавшим. */
    public int latencyTicks() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        var e = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return e == null ? 0 : Math.min(10, e.getLatency() / 50);
    }

    public boolean inventoryFull() {
        return mc.player.getInventory().getEmptySlot() == -1;
    }

    private void moveToSelected(int invSlot) {
        PlayerInventory inv = mc.player.getInventory();
        if (invSlot < 9) {
            inv.setSelectedSlot(invSlot);
            return;
        }
        int hot = inv.getSelectedSlot();
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, invSlot, hot, SlotActionType.SWAP, mc.player);
    }

    // ---------------------------------------------------------------- блоки

    /** Прямая видимость от глаз до центра блока: возвращает первый встреченный блок. */
    public BlockHitResult sight(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d c = Vec3d.ofCenter(pos);
        return mc.world.raycast(new RaycastContext(eye, c, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    public double eyeDistance(BlockPos pos) {
        return mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    public boolean inReach(BlockPos pos) {
        return eyeDistance(pos) <= ModConfig.get().reach + 0.6;
    }

    /** Один тик ломания блока. Если на линии взгляда мешает другой блок, ломает его. */
    public MineResult mine(BlockPos pos) {
        return mine(pos, true);
    }

    /**
     * @param allowBlockers если true, мешающий на линии взгляда блок будет сломан; если false — вернётся FAIL
     */
    public MineResult mine(BlockPos pos, boolean allowBlockers) {
        ClientWorld w = mc.world;
        BlockState st = w.getBlockState(pos);
        if (st.isAir() || st.isLiquid()) return MineResult.DONE;
        if (!inReach(pos)) return MineResult.FAIL;

        BlockPos target = pos;
        Direction face = null;
        BlockHitResult hit = sight(pos);
        if (hit.getType() == HitResult.Type.BLOCK) {
            if (hit.getBlockPos().equals(pos)) {
                face = hit.getSide();
            } else {
                if (!allowBlockers) return MineResult.FAIL;
                BlockState blocker = w.getBlockState(hit.getBlockPos());
                if (blocker.getHardness(w, hit.getBlockPos()) < 0) return MineResult.FAIL;
                target = hit.getBlockPos();
                face = hit.getSide();
            }
        }
        if (face == null) face = Direction.getFacing(mc.player.getEyePos().subtract(Vec3d.ofCenter(target)));

        BlockState ts = w.getBlockState(target);
        if (ts.getHardness(w, target) < 0) return MineResult.FAIL;
        selectBestTool(ts);
        Human h = Human.get();
        if (h.on()) {
            if (!target.equals(humanTarget)) {
                humanTarget = target;
                humanWait = h.reaction();
            }
            lookAt(h.aimPoint(Vec3d.ofCenter(target), target.asLong()));
            if (humanWait > 0 || toolWait > 0) {
                if (humanWait > 0) humanWait--;
                if (toolWait > 0) toolWait--;
                return MineResult.WORKING;
            }
            if (h.mineHiccup()) return MineResult.WORKING;
        } else {
            lookAt(Vec3d.ofCenter(target));
        }
        mc.interactionManager.updateBlockBreakingProgress(target, face);
        mc.player.swingHand(Hand.MAIN_HAND);
        return MineResult.WORKING;
    }

    /** Нажать ПКМ по блоку (поставить предмет / использовать). */
    public ActionResult useOn(BlockHitResult hit) {
        ActionResult r = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (r instanceof ActionResult.Success s && s.swingSource() == ActionResult.SwingSource.CLIENT) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        return r;
    }

    /** Поставить блок на заданную грань опорного блока. */
    public ActionResult placeAgainst(BlockPos support, Direction face) {
        Vec3d hitVec = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        return useOn(new BlockHitResult(hitVec, face, support, false));
    }

    /** Закрыть открытый контейнер/экран, который мог открыться от нашего клика. */
    public void closeStrayScreen() {
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>) {
            mc.player.closeHandledScreen();
        }
    }
}

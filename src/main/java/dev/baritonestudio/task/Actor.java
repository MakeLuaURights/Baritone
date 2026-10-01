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
    private boolean fwd, sprint, jump, sneak, anyKeyHeld;

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

    /** Выставить сохранённые флаги в клавиши игры. Вызывается раз в тик после логики задач. */
    public void apply() {
        GameOptions o = mc.options;
        boolean any = fwd || sprint || jump || sneak;
        if (any) {
            o.forwardKey.setPressed(fwd);
            o.sprintKey.setPressed(sprint);
            o.jumpKey.setPressed(jump);
            o.sneakKey.setPressed(sneak);
            anyKeyHeld = true;
        } else if (anyKeyHeld) {
            releaseKeys();
        }
        fwd = sprint = jump = sneak = false;
    }

    /** Отпустить все клавиши, которые мы нажимали, и вернуть физическое состояние клавиатуры. */
    public void releaseKeys() {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.sprintKey.setPressed(false);
        o.jumpKey.setPressed(false);
        o.sneakKey.setPressed(false);
        anyKeyHeld = false;
        fwd = sprint = jump = sneak = false;
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

    public float lookAngles(float yaw, float pitch) {
        ClientPlayerEntity p = mc.player;
        float speed = ModConfig.get().rotateSpeed;
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
        if (best != cur) moveToSelected(best);
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

    /** Предмет, пригодный как опора для столба. */
    public static boolean isScaffold(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && Terrain.scaffoldBlock(bi.getBlock().getDefaultState());
    }

    public boolean hasScaffold() {
        return hasItem(Actor::isScaffold);
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
        lookAt(Vec3d.ofCenter(target));
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

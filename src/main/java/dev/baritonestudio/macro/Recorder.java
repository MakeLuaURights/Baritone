package dev.baritonestudio.macro;

import dev.baritonestudio.mixin.SignEditScreenAccessor;
import dev.baritonestudio.util.L;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Записывает действия игрока: перемещения, ломание, клики по блокам и текст на табличках. */
public final class Recorder {
    private record Pending(Step step, BlockPos placePos, BlockState before, long dueTick) {}

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private boolean active;
    private final List<Step> raw = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private BlockPos lastStand;
    private int facingAtFirstAction = -1;
    private long startedTick;

    public boolean active() {
        return active;
    }

    public int rawCount() {
        return raw.size();
    }

    public int actionCount() {
        int n = 0;
        for (Step s : raw) if (s.isAction()) n++;
        return n;
    }

    public void start() {
        raw.clear();
        pending.clear();
        lastStand = null;
        facingAtFirstAction = -1;
        active = true;
        startedTick = mc.world != null ? mc.world.getTime() : 0;
    }

    public void cancel() {
        active = false;
        raw.clear();
        pending.clear();
    }

    /** Закончить запись. Возвращает null, если не было ни одного действия. */
    public Macro stop(String name) {
        if (!active) return null;
        active = false;
        resolvePending(true);
        Step first = null;
        for (Step s : raw) {
            if (s.isAction()) {
                first = s;
                break;
            }
        }
        if (first == null) {
            raw.clear();
            return null;
        }
        // опорная точка: блок первого действия
        int rx = first.x, ry = first.y, rz = first.z;
        Macro m = new Macro();
        m.name = name;
        m.created = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        m.facing = facingAtFirstAction < 0 ? 0 : facingAtFirstAction;
        for (Step s : raw) {
            Step c = s.copy();
            c.x -= rx;
            c.y -= ry;
            c.z -= rz;
            if (c.type == Step.Type.USE && c.placed) {
                c.px -= rx;
                c.py -= ry;
                c.pz -= rz;
            }
            m.steps.add(c);
        }
        raw.clear();
        return m;
    }

    // ------------------------------------------------------------ события

    public void tick() {
        if (!active) return;
        resolvePending(false);
    }

    private void addStand() {
        ClientPlayerEntity p = mc.player;
        BlockPos stand = p.getBlockPos();
        if (!stand.equals(lastStand)) {
            raw.add(Step.move(stand.getX(), stand.getY(), stand.getZ()));
            lastStand = stand;
        }
    }

    private void markFirstAction() {
        if (facingAtFirstAction < 0) {
            facingAtFirstAction = Direction.fromHorizontalDegrees(mc.player.getYaw()).getHorizontalQuarterTurns();
        }
    }

    public void onBreak(BlockPos pos, BlockState state) {
        if (!active) return;
        addStand();
        markFirstAction();
        Step s = new Step();
        s.type = Step.Type.BREAK;
        s.x = pos.getX();
        s.y = pos.getY();
        s.z = pos.getZ();
        s.block = Registries.BLOCK.getId(state.getBlock()).toString();
        raw.add(s);
    }

    public void onUse(BlockHitResult hit, Hand hand) {
        if (!active || hand != Hand.MAIN_HAND) return;
        ClientPlayerEntity p = mc.player;
        addStand();
        markFirstAction();
        ItemStack stack = p.getMainHandStack();
        BlockPos clicked = hit.getBlockPos();
        Step s = new Step();
        s.type = Step.Type.USE;
        s.x = clicked.getX();
        s.y = clicked.getY();
        s.z = clicked.getZ();
        s.side = hit.getSide().getIndex();
        s.hx = clamp01(hit.getPos().x - clicked.getX());
        s.hy = clamp01(hit.getPos().y - clicked.getY());
        s.hz = clamp01(hit.getPos().z - clicked.getZ());
        s.item = stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString();
        s.sneak = p.isSneaking();
        s.yaw = p.getYaw();
        s.pitch = p.getPitch();
        raw.add(s);
        if (stack.getItem() instanceof BlockItem) {
            BlockPos place = new ItemPlacementContext(p, hand, stack, hit).getBlockPos();
            pending.add(new Pending(s, place, mc.world.getBlockState(place), mc.world.getTime() + 4));
        }
    }

    public void onSignClosed(AbstractSignEditScreen screen) {
        if (!active) return;
        SignEditScreenAccessor acc = (SignEditScreenAccessor) screen;
        String[] msg = acc.baritonestudio$getMessages();
        boolean any = false;
        for (String l : msg) if (l != null && !l.isEmpty()) any = true;
        if (!any) return;
        BlockPos pos = acc.baritonestudio$getBlockEntity().getPos();
        resolvePending(true);
        Step s = new Step();
        s.type = Step.Type.SIGN;
        s.x = pos.getX();
        s.y = pos.getY();
        s.z = pos.getZ();
        s.front = acc.baritonestudio$isFront();
        s.lines = new String[]{msg[0], msg[1], msg[2], msg[3]};
        raw.add(s);
    }

    private void resolvePending(boolean force) {
        if (pending.isEmpty() || mc.world == null) return;
        long now = mc.world.getTime();
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending pd = it.next();
            if (!force && now < pd.dueTick) continue;
            BlockState after = mc.world.getBlockState(pd.placePos);
            if (!after.isAir() && !after.equals(pd.before) && !after.isReplaceable()) {
                pd.step.placed = true;
                pd.step.px = pd.placePos.getX();
                pd.step.py = pd.placePos.getY();
                pd.step.pz = pd.placePos.getZ();
                pd.step.block = Registries.BLOCK.getId(after.getBlock()).toString();
            }
            it.remove();
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public String summary() {
        return L.t("rec.summary", actionCount());
    }
}

package dev.baritonestudio.macro;

import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.mixin.SignEditScreenAccessor;
import dev.baritonestudio.path.Goal;
import dev.baritonestudio.path.Navigator;
import dev.baritonestudio.task.Actor;
import dev.baritonestudio.task.Task;
import dev.baritonestudio.util.L;
import dev.baritonestudio.util.Storage;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Повторяет запись в каждом из выбранных мест. */
public final class MacroTask extends Task {
    private enum R { RUNNING, DONE, SKIP }

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Macro macro;
    private final List<Target> targets;
    private final Navigator nav = new Navigator();

    private int ti, si;
    private int wait;
    private int skipped;
    private String status = "";

    // состояние текущего шага
    private int stepTicks;
    private int attempts;
    private int alignTicks;
    private int itemWait;
    private boolean navStarted, triedNear, clicked, triedOpen;
    private int verifyAt;

    public MacroTask(Macro macro, List<Target> targets) {
        this.macro = macro;
        this.targets = List.copyOf(targets);
    }

    @Override
    public String name() {
        return L.t("task.replay", macro.name);
    }

    @Override
    public String status() {
        return L.t("status.replay_progress", Math.min(ti + 1, targets.size()), targets.size(), Math.min(si + 1, macro.steps.size()), macro.steps.size()) + " • " + status;
    }

    @Override
    public double progress() {
        int total = Math.max(1, targets.size() * macro.steps.size());
        return Math.min(1.0, (double) (ti * macro.steps.size() + si) / total);
    }

    @Override
    public Navigator navigator() {
        return nav;
    }

    @Override
    public void start(Actor a) {
        nav.breakMode = Navigator.BreakMode.NEVER;
        if (targets.isEmpty()) fail(L.t("status.no_targets"));
        else if (macro.steps.isEmpty()) fail(L.t("status.empty_macro"));
    }

    @Override
    public void stop(Actor a) {
        nav.clear();
        a.sneak(false);
    }

    private void resetStep() {
        stepTicks = 0;
        attempts = 0;
        alignTicks = 0;
        itemWait = 0;
        navStarted = false;
        triedNear = false;
        clicked = false;
        triedOpen = false;
        verifyAt = 0;
        nav.clear();
    }

    private void advance() {
        si++;
        resetStep();
        wait = ModConfig.get().actionDelay;
        if (si >= macro.steps.size()) {
            ti++;
            si = 0;
            wait += 2;
        }
    }

    @Override
    public void tick(Actor a) {
        if (wait > 0) {
            wait--;
            return;
        }
        if (ti >= targets.size()) {
            finish(skipped == 0 ? L.t("status.replay_done", targets.size()) : L.t("status.replay_done_skipped", targets.size(), skipped));
            return;
        }
        Step s = macro.steps.get(si);
        Transform tr = new Transform(targets.get(ti));
        stepTicks++;
        if (stepTicks > 1200) {
            warn(L.t("warn.timeout", describe(s, tr)));
            skipped++;
            advance();
            return;
        }
        R r = switch (s.type) {
            case MOVE -> doMove(a, s, tr);
            case BREAK -> doBreak(a, s, tr);
            case USE -> doUse(a, s, tr);
            case SIGN -> doSign(a, s, tr);
        };
        if (r == R.DONE) {
            advance();
        } else if (r == R.SKIP) {
            skipped++;
            advance();
        }
    }

    private String describe(Step s, Transform tr) {
        BlockPos p = tr.pos(s);
        return s.type + " " + p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private void warn(String msg) {
        Storage.LOG.info("MacroTask: {}", msg);
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("[Baritone Studio] " + msg).formatted(net.minecraft.util.Formatting.GOLD), false);
    }

    // ------------------------------------------------------------ MOVE

    private R doMove(Actor a, Step s, Transform tr) {
        ClientPlayerEntity p = a.player();
        BlockPos goal = tr.pos(s);
        status = L.t("status.walking");
        if (p.getBlockPos().equals(goal) && (p.isOnGround() || p.isTouchingWater())) {
            return R.DONE;
        }
        if (!navStarted) {
            nav.setGoal(new Goal.Block(goal));
            navStarted = true;
        }
        switch (nav.tick(a)) {
            case ARRIVED:
                return R.DONE;
            case FAILED:
                if (!triedNear) {
                    triedNear = true;
                    nav.setGoal(new Goal.Near(goal.getX(), goal.getY(), goal.getZ(), 2));
                    return R.RUNNING;
                }
                warn(L.t("warn.cannot_reach", goal.getX(), goal.getY(), goal.getZ()));
                return R.SKIP;
            default:
                return R.RUNNING;
        }
    }

    private boolean approach(Actor a, BlockPos block) {
        if (!navStarted) {
            nav.setGoal(new Goal.Adjacent(block));
            navStarted = true;
        }
        Navigator.Status st = nav.tick(a);
        if (st == Navigator.Status.FAILED) return false;
        if (st == Navigator.Status.ARRIVED) {
            navStarted = false;
            if (++attempts > 3) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ BREAK

    private R doBreak(Actor a, Step s, Transform tr) {
        BlockPos pos = tr.pos(s);
        BlockState st = a.world().getBlockState(pos);
        if (st.isAir() || st.isLiquid()) return R.DONE;
        status = L.t("status.mining", st.getBlock().getName().getString());
        if (st.getHardness(a.world(), pos) < 0) {
            warn(L.t("warn.unbreakable", st.getBlock().getName().getString()));
            return R.SKIP;
        }
        Actor.MineResult r = a.inReach(pos) ? a.mine(pos, false) : Actor.MineResult.FAIL;
        switch (r) {
            case DONE:
                return R.DONE;
            case WORKING:
                nav.clear();
                navStarted = false;
                return R.RUNNING;
            default:
                if (!approach(a, pos)) {
                    warn(L.t("warn.cannot_reach", pos.getX(), pos.getY(), pos.getZ()));
                    return R.SKIP;
                }
                return R.RUNNING;
        }
    }

    // ------------------------------------------------------------ USE

    private static Item item(String id) {
        Identifier i = Identifier.tryParse(id);
        return i == null ? Items.AIR : Registries.ITEM.getOptionalValue(i).orElse(Items.AIR);
    }

    private static Block block(String id) {
        Identifier i = Identifier.tryParse(id);
        return i == null ? null : Registries.BLOCK.getOptionalValue(i).orElse(null);
    }

    private R doUse(Actor a, Step s, Transform tr) {
        BlockPos clickedPos = tr.pos(s);
        Direction side = tr.dir(s.side);
        Vec3d hitVec = tr.hit(s);
        ClientPlayerEntity p = a.player();

        Block expected = s.placed ? block(s.block) : null;
        if (s.placed) {
            BlockPos pp = tr.placedPos(s);
            BlockState cur = a.world().getBlockState(pp);
            boolean occupied = !cur.isAir() && !cur.isReplaceable();
            if (occupied) {
                if (expected == null || cur.isOf(expected)) return R.DONE; // уже стоит
                warn(L.t("warn.occupied", pp.getX(), pp.getY(), pp.getZ()));
                return R.SKIP;
            }
        }

        Item it = item(s.item);
        status = it == Items.AIR ? L.t("status.interacting") : L.t("status.placing", it.getName().getString());

        // предмет в руку
        if (it == Items.AIR) {
            a.selectEmptyHand();
        } else if (!a.selectItem(st -> st.isOf(it))) {
            if (++itemWait > ModConfig.get().itemWaitSeconds * 20) {
                fail(L.t("status.no_item", it.getName().getString()));
            } else {
                status = L.t("status.waiting_item", it.getName().getString());
            }
            return R.RUNNING;
        }
        itemWait = 0;

        // достаём до точки
        if (!clicked) {
            if (p.getEyePos().distanceTo(hitVec) > ModConfig.get().reach + 0.5) {
                if (!approach(a, clickedPos)) {
                    warn(L.t("warn.cannot_reach", clickedPos.getX(), clickedPos.getY(), clickedPos.getZ()));
                    return R.SKIP;
                }
                return R.RUNNING;
            }
            nav.clear();
            navStarted = false;

            // выравниваем взгляд и приседание как при записи
            a.sneak(s.sneak);
            float rem = a.lookAngles(tr.yaw(s), s.pitch);
            if (rem > 1.5f) {
                alignTicks = 0;
                return R.RUNNING;
            }
            if (++alignTicks < 3) return R.RUNNING;

            a.useOn(new BlockHitResult(hitVec, side, clickedPos, false));
            clicked = true;
            verifyAt = stepTicks + 5;
            return R.RUNNING;
        }

        a.sneak(s.sneak);
        if (stepTicks < verifyAt) return R.RUNNING;

        if (s.placed) {
            BlockState cur = a.world().getBlockState(tr.placedPos(s));
            if (!cur.isAir() && !cur.isReplaceable()) {
                a.closeStrayScreen();
                return R.DONE;
            }
            if (++attempts >= 3) {
                warn(L.t("warn.place_failed", it.getName().getString()));
                return R.SKIP;
            }
            clicked = false;
            alignTicks = 0;
            return R.RUNNING;
        }
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>) a.closeStrayScreen();
        return R.DONE;
    }

    // ------------------------------------------------------------ SIGN

    private R doSign(Actor a, Step s, Transform tr) {
        BlockPos pos = tr.pos(s);
        status = L.t("status.writing_sign");
        if (mc.currentScreen instanceof AbstractSignEditScreen screen) {
            SignEditScreenAccessor acc = (SignEditScreenAccessor) screen;
            if (acc.baritonestudio$getBlockEntity().getPos().equals(pos)) {
                String[] msg = acc.baritonestudio$getMessages();
                int n = macro.counterStart + ti * macro.counterStep;
                for (int i = 0; i < 4 && i < msg.length; i++) {
                    msg[i] = Transform.fill(s.lines != null && i < s.lines.length ? s.lines[i] : "", n);
                }
                screen.close();
                wait += 2;
                return R.DONE;
            }
        }
        if (stepTicks < 30) return R.RUNNING; // экран редактирования открывается после установки
        if (!triedOpen) {
            triedOpen = true;
            BlockEntity be = a.world().getBlockEntity(pos);
            if (be instanceof SignBlockEntity sbe && !sbe.isWaxed()) {
                if (a.player().getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= ModConfig.get().reach + 0.5) {
                    a.selectEmptyHand();
                    a.useOn(new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
                }
            } else {
                warn(L.t("warn.no_sign", pos.getX(), pos.getY(), pos.getZ()));
                return R.SKIP;
            }
            return R.RUNNING;
        }
        if (stepTicks > 120) {
            warn(L.t("warn.sign_failed", pos.getX(), pos.getY(), pos.getZ()));
            return R.SKIP;
        }
        return R.RUNNING;
    }
}

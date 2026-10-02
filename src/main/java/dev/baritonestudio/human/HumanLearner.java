package dev.baritonestudio.human;

import dev.baritonestudio.Studio;
import dev.baritonestudio.util.L;
import dev.baritonestudio.util.Storage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Режим обучения: пока вы сами рубите деревья, мод записывает ваш почерк —
 * как вы наводите мышь, как дрожит прицел при ударах, с какого расстояния бьёте,
 * куда целитесь в брёвнах и сколько «думаете» между бревнами. Одно дерево = один профиль (до 10).
 */
public final class HumanLearner {
    /** Сколько деревьев нужно записать (в тестах можно уменьшить). */
    public static int targetTrees = HumanProfiles.MAX;

    /** Один сломанный (или бьющийся) ствол внутри дерева. */
    private static final class Sample {
        float reaction, eyeDist, lookUp, breakTicks, think = -1;
        float[] offset = new float[3];
        float jitterYaw, jitterPitch;
        List<float[]> aim; // повороты (dyaw, dpitch) по тикам
    }

    private static final class Session {
        BlockPos pos;
        long startTick;
        Sample s = new Sample();
        final List<Float> dy = new ArrayList<>(), dp = new ArrayList<>();
    }

    private static boolean active;
    private static float lastYaw, lastPitch;
    private static boolean init;
    // сегмент поворота
    private static boolean inSeg;
    private static int segIdle;
    private static final List<float[]> seg = new ArrayList<>();
    private static List<float[]> lastSeg;
    private static long lastSegEnd = -1000;
    private static final ArrayDeque<Float> pitchHist = new ArrayDeque<>();
    // прицел
    private static boolean prevOnLog;
    private static long acquireTick;
    private static Session cur;
    // дерево
    private static final List<Sample> tree = new ArrayList<>();
    private static Vec3d treeCenter;
    private static long lastBreakTick = -1000;

    private HumanLearner() {}

    public static boolean active() {
        return active;
    }

    public static int logsInTree() {
        return tree.size() + (cur != null ? 1 : 0);
    }

    public static void start() {
        reset();
        active = true;
        Studio.get().say(Formatting.AQUA, L.t("msg.learn_start", targetTrees));
    }

    public static void stop() {
        if (!active) return;
        closeTree();
        active = false;
        reset();
        Studio.get().say(Formatting.YELLOW, L.t("msg.learn_stop", HumanProfiles.all().size()));
    }

    private static void reset() {
        init = false;
        inSeg = false;
        seg.clear();
        lastSeg = null;
        pitchHist.clear();
        prevOnLog = false;
        cur = null;
        tree.clear();
        treeCenter = null;
    }

    public static void tick(MinecraftClient mc) {
        if (!active) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        long time = mc.world.getTime();
        float yaw = p.getYaw(), pitch = p.getPitch();
        float dy = init ? MathHelper.wrapDegrees(yaw - lastYaw) : 0, dp = init ? pitch - lastPitch : 0;
        lastYaw = yaw;
        lastPitch = pitch;
        init = true;
        if (mc.currentScreen != null || Studio.get().tasks.busy()) {
            return;
        }

        // ---- сегменты поворота мыши
        double speed = Math.hypot(dy, dp);
        if (speed > 0.6 || (inSeg && speed > 0.2)) {
            if (!inSeg) {
                inSeg = true;
                seg.clear();
            }
            seg.add(new float[]{dy, dp});
            segIdle = 0;
        } else if (inSeg) {
            seg.add(new float[]{dy, dp});
            if (++segIdle >= 3) {
                inSeg = false;
                List<float[]> body = new ArrayList<>(seg.subList(0, Math.max(1, seg.size() - segIdle)));
                double nx = 0, ny = 0;
                for (float[] v : body) {
                    nx += v[0];
                    ny += v[1];
                }
                if (Math.hypot(nx, ny) >= 6) {
                    lastSeg = body;
                    lastSegEnd = time - segIdle;
                }
            }
        }
        pitchHist.addLast(pitch);
        if (pitchHist.size() > 40) pitchHist.removeFirst();

        // ---- что под прицелом
        BlockPos logPos = null;
        Vec3d hitPos = null;
        HitResult hr = mc.crosshairTarget;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && mc.world.getBlockState(bhr.getBlockPos()).isIn(BlockTags.LOGS)) {
            logPos = bhr.getBlockPos();
            hitPos = bhr.getPos();
        }
        boolean onLog = logPos != null;
        if (onLog && !prevOnLog) acquireTick = time;
        prevOnLog = onLog;
        boolean attacking = mc.options.attackKey.isPressed();

        // ---- сессия ломания бревна
        if (cur != null) {
            BlockState now = mc.world.getBlockState(cur.pos);
            if (now.isAir()) {
                finishSample(time);
            } else if (!attacking || !onLog || !logPos.equals(cur.pos)) {
                cur = null; // отпустили/увели прицел – запись этого бревна отбрасываем
            } else {
                cur.dy.add(dy);
                cur.dp.add(dp);
            }
        }
        if (cur == null && attacking && onLog) {
            if (!tree.isEmpty() && treeCenter != null && Vec3d.ofCenter(logPos).distanceTo(treeCenter) > 8) closeTree();
            startSession(mc, p, logPos, hitPos, time);
        }

        // ---- конец дерева: долго не рубим
        if (!tree.isEmpty() && cur == null && time - lastBreakTick > 120) closeTree();
    }

    private static void startSession(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos, Vec3d hit, long time) {
        Session s = new Session();
        s.pos = pos;
        s.startTick = time;
        Sample sm = s.s;
        sm.reaction = MathHelper.clamp(time - acquireTick, 0, 60);
        sm.eyeDist = (float) p.getEyePos().distanceTo(hit);
        sm.offset[0] = (float) MathHelper.clamp(hit.x - pos.getX(), 0, 1);
        sm.offset[1] = (float) MathHelper.clamp(hit.y - pos.getY(), 0, 1);
        sm.offset[2] = (float) MathHelper.clamp(hit.z - pos.getZ(), 0, 1);
        if (lastSeg != null && time - lastSegEnd <= 45 && lastSegEnd >= acquireTick - 45) {
            sm.aim = lastSeg;
            lastSeg = null;
        }
        float minPitch = p.getPitch();
        for (float v : pitchHist) minPitch = Math.min(minPitch, v);
        sm.lookUp = Math.max(0, p.getPitch() - minPitch);
        if (!tree.isEmpty() && lastBreakTick > 0) sm.think = MathHelper.clamp(time - lastBreakTick, 0, 100);
        if (tree.isEmpty()) treeCenter = Vec3d.ofCenter(pos);
        cur = s;
    }

    private static void finishSample(long time) {
        Sample sm = cur.s;
        sm.breakTicks = time - cur.startTick;
        sm.jitterYaw = std(cur.dy, 2);
        sm.jitterPitch = std(cur.dp, 2);
        tree.add(sm);
        lastBreakTick = time;
        cur = null;
    }

    private static float std(List<Float> v, int skip) {
        if (v.size() <= skip + 2) return 0;
        double sum = 0, sq = 0;
        int n = 0;
        for (int i = skip; i < v.size(); i++) {
            sum += v.get(i);
            sq += v.get(i) * v.get(i);
            n++;
        }
        double mean = sum / n;
        return (float) Math.sqrt(Math.max(0, sq / n - mean * mean));
    }

    private static void closeTree() {
        if (tree.isEmpty()) {
            treeCenter = null;
            return;
        }
        HumanProfile pr = build(tree);
        HumanProfiles.add(pr);
        tree.clear();
        treeCenter = null;
        Storage.LOG.info("HumanLearner: записан профиль #{}: {}", HumanProfiles.all().size(), pr.summary());
        Studio.get().say(Formatting.GREEN, L.t("msg.learn_tree", HumanProfiles.all().size(), targetTrees, pr.logs));
        if (HumanProfiles.all().size() >= targetTrees) {
            active = false;
            reset();
            Studio.get().say(Formatting.GREEN, L.t("msg.learn_done", HumanProfiles.all().size()));
        }
    }

    // ------------------------------------------------------------ сборка профиля

    static HumanProfile build(List<Sample> ss) {
        HumanProfile p = new HumanProfile();
        p.created = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        p.logs = ss.size();
        p.mouseSens = (float) MinecraftClient.getInstance().options.getMouseSensitivity().getValue().doubleValue();

        double react = 0, hit = 0, brk = 0, jy = 0, jp = 0, up = 0, upDeg = 0;
        double[] off = new double[3];
        List<Float> thinks = new ArrayList<>();
        List<Float> reacts = new ArrayList<>();
        int upCount = 0;
        for (Sample s : ss) {
            react += s.reaction;
            reacts.add(s.reaction);
            hit += s.eyeDist;
            brk += s.breakTicks;
            jy += s.jitterYaw;
            jp += s.jitterPitch;
            for (int i = 0; i < 3; i++) off[i] += s.offset[i];
            if (s.think >= 0) thinks.add(s.think);
            if (s.lookUp > 10) {
                upCount++;
                upDeg += s.lookUp;
            }
        }
        int n = ss.size();
        p.reaction = (float) (react / n);
        p.reactionSpread = Math.max(1, spread(reacts));
        p.hitDistance = (float) (hit / n);
        p.breakTicks = (float) (brk / n);
        p.jitterYaw = Math.max(0.02f, (float) (jy / n));
        p.jitterPitch = Math.max(0.02f, (float) (jp / n));
        for (int i = 0; i < 3; i++) p.aimOffset[i] = (float) (off[i] / n);
        for (int i = 0; i < 3; i++) {
            double v = 0;
            for (Sample s : ss) v += (s.offset[i] - p.aimOffset[i]) * (s.offset[i] - p.aimOffset[i]);
            p.aimOffsetStd[i] = Math.max(0.05f, (float) Math.sqrt(v / n));
        }
        if (!thinks.isEmpty()) {
            double m = 0;
            for (float t : thinks) m += t;
            p.think = (float) (m / thinks.size());
            p.thinkSpread = Math.max(1, spread(thinks));
        }
        p.lookUpProb = upCount / (float) n;
        p.lookUpDeg = upCount == 0 ? 0 : (float) (upDeg / upCount);

        // кривая наведения: усредняем нормированные кривые всех поворотов дерева
        float[] curve = new float[HumanProfile.CURVE_POINTS];
        int curves = 0;
        double speedSum = 0;
        double minTicks = Double.MAX_VALUE;
        for (Sample s : ss) {
            if (s.aim == null || s.aim.size() < 2) continue;
            double nx = 0, ny = 0;
            for (float[] v : s.aim) {
                nx += v[0];
                ny += v[1];
            }
            double len = Math.hypot(nx, ny);
            if (len < 1) continue;
            double ux = nx / len, uy = ny / len;
            int m = s.aim.size();
            double[] prog = new double[m + 1];
            double cx = 0, cy = 0;
            for (int i = 0; i < m; i++) {
                cx += s.aim.get(i)[0];
                cy += s.aim.get(i)[1];
                prog[i + 1] = (cx * ux + cy * uy) / len;
            }
            for (int k = 0; k < HumanProfile.CURVE_POINTS; k++) {
                double x = k / (double) (HumanProfile.CURVE_POINTS - 1) * m;
                int i = Math.min(m - 1, (int) Math.floor(x));
                double f = x - i;
                curve[k] += (float) (prog[i] * (1 - f) + prog[i + 1] * f);
            }
            curves++;
            speedSum += len / m;
            minTicks = Math.min(minTicks, m);
        }
        if (curves > 0) {
            for (int k = 0; k < curve.length; k++) curve[k] /= curves;
            curve[0] = 0;
            p.aimCurve = curve;
            p.aimSpeed = (float) Math.max(2.5, speedSum / curves);
            p.aimMinTicks = (float) Math.max(2, minTicks);
        }
        return p;
    }

    private static float spread(List<Float> v) {
        if (v.size() < 2) return 2;
        double m = 0;
        for (float x : v) m += x;
        m /= v.size();
        double s = 0;
        for (float x : v) s += (x - m) * (x - m);
        return (float) Math.sqrt(s / v.size());
    }
}

package dev.baritonestudio.gametest;

import dev.baritonestudio.Keys;
import dev.baritonestudio.Studio;
import dev.baritonestudio.gui.StudioScreen;
import dev.baritonestudio.macro.Macro;
import dev.baritonestudio.macro.Step;
import dev.baritonestudio.macro.Target;
import dev.baritonestudio.mixin.SignEditScreenAccessor;
import dev.baritonestudio.preset.PresetStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Сквозная проверка в настоящем клиенте: пресеты, путь, запись и повтор с табличками, интерфейс. */
public class StudioClientTest implements FabricClientGameTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static void fill(TestServerContext srv, BlockPos a, BlockPos b, String block) {
        srv.runCommand("fill " + fmt(a) + " " + fmt(b) + " " + block);
    }

    private void waitTaskEnd(ClientGameTestContext ctx, int ticks, String what) {
        ctx.waitFor(mc -> !Studio.get().tasks.busy(), ticks);
        var t = Studio.get().tasks.last();
        check(t != null && t.isDone() && !t.isFailed(), what + " не удалось: " + (t == null ? "нет задачи" : t.result()));
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            TestServerContext srv = world.getServer();
            srv.runCommand("gamemode survival @a");
            srv.runCommand("gamerule doMobSpawning false");
            srv.runCommand("time set noon");
            srv.runCommand("effect give @a minecraft:resistance 1000 4 true");
            ctx.waitTicks(10);
            BlockPos base = ctx.computeOnClient(mc -> mc.player.getBlockPos());
            System.out.println("[E2E] base=" + base);

            // ---------- интерфейс
            ctx.takeScreenshot("00-world");
            ctx.getInput().pressKey(Keys.OPEN);
            ctx.waitFor(mc -> mc.currentScreen instanceof StudioScreen);
            ctx.waitTicks(5);
            ctx.takeScreenshot("01-menu-presets");
            for (var t : StudioScreen.TabId.values()) {
                StudioScreen.openTab(t);
                ctx.waitTicks(3);
                ctx.takeScreenshot("02-tab-" + t.name().toLowerCase());
            }
            ctx.getInput().pressKey(Keys.OPEN);
            ctx.waitFor(mc -> mc.currentScreen == null);

            // ---------- ходьба с прыжками
            srv.runCommand("give @a minecraft:diamond_pickaxe");
            fill(srv, base.add(3, 0, -3), base.add(3, 0, 3), "stone");
            fill(srv, base.add(6, 0, -3), base.add(6, 1, 3), "stone");
            ctx.waitTicks(5);
            BlockPos goal = base.add(10, 0, 0);
            ctx.runOnClient(mc -> Studio.get().startGoto(goal.getX(), goal.getY(), goal.getZ()));
            waitTaskEnd(ctx, 600, "Ходьба");
            BlockPos at = ctx.computeOnClient(mc -> mc.player.getBlockPos());
            System.out.println("[E2E] walked to " + at);
            check(at.getSquaredDistance(goal) <= 2, "не дошёл до цели: " + at);

            // ---------- добыча (камень)
            BlockPos mass = base.add(-8, 0, -3);
            fill(srv, mass, mass.add(3, 2, 3), "stone");
            srv.runCommand("setblock " + fmt(mass.add(1, 0, 1)) + " minecraft:diamond_ore");
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> Studio.get().startPreset(PresetStore.find("stone"), 8, 20));
            waitTaskEnd(ctx, 2400, "Добыча камня");
            int cobble = ctx.computeOnClient(mc -> mc.player.getInventory().count(Items.COBBLESTONE));
            System.out.println("[E2E] cobblestone in inventory: " + cobble);
            check(cobble >= 6, "мало булыжника: " + cobble);

            // ---------- алмазная руда внутри камня (прокапывание)
            ctx.runOnClient(mc -> Studio.get().startPreset(PresetStore.find("diamond"), 1, 20));
            waitTaskEnd(ctx, 2400, "Добыча алмазов");
            int dia = ctx.computeOnClient(mc -> mc.player.getInventory().count(Items.DIAMOND));
            check(dia >= 1, "нет алмаза: " + dia);

            // ---------- запись и повтор
            testMacro(ctx, srv, base);
            ctx.takeScreenshot("99-end");
        }
    }

    private void testMacro(ClientGameTestContext ctx, TestServerContext srv, BlockPos base) {
        srv.runCommand("gamemode creative @a");
        srv.runCommand("tp @a " + fmt(base) + " -90 0");
        BlockPos wall = base.add(3, 0, 0);
        fill(srv, base.add(3, -1, -2), base.add(3, 3, 2), "stone");
        fill(srv, base.add(-2, 0, -4), base.add(2, 3, -4), "stone");
        fill(srv, base.add(-3, 0, -1), base.add(2, 0, 3), "air");
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:oak_sign 30");
        ctx.waitTicks(10);

        // ---- запись: поставить табличку на стену и сломать блок
        ctx.runOnClient(mc -> {
            check(mc.player.getBlockPos().equals(base), "игрок не на месте: " + mc.player.getBlockPos());
            Studio.get().startRecording();
            PlayerInventory inv = mc.player.getInventory();
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.OAK_SIGN)) inv.setSelectedSlot(i);
            }
        });
        ctx.waitTicks(2);
        BlockPos clicked = base.add(3, 1, 0);
        ctx.runOnClient(mc -> {
            BlockHitResult hit = new BlockHitResult(new Vec3d(clicked.getX(), clicked.getY() + 0.5, clicked.getZ() + 0.5), Direction.WEST, clicked, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        });
        ctx.waitFor(mc -> mc.currentScreen instanceof AbstractSignEditScreen, 100);
        ctx.runOnClient(mc -> {
            SignEditScreenAccessor acc = (SignEditScreenAccessor) mc.currentScreen;
            String[] m = acc.baritonestudio$getMessages();
            m[0] = "Дом";
            m[1] = "номер 1";
            mc.currentScreen.close();
        });
        ctx.waitTicks(6);
        ctx.runOnClient(mc -> {
            BlockPos b = base.add(3, 2, 1);
            mc.interactionManager.attackBlock(b, Direction.WEST);
        });
        ctx.waitTicks(6);
        Macro macro = ctx.computeOnClient(mc -> Studio.get().stopRecording("e2e"));
        check(macro != null, "запись не создана");
        System.out.println("[E2E] recorded steps: " + macro.steps.size());
        for (Step s : macro.steps) {
            System.out.println("[E2E]   " + s.type + " " + s.x + "," + s.y + "," + s.z + " placed=" + s.placed + " item=" + s.item + " block=" + s.block + " lines=" + String.join("|", s.lines));
        }
        check(macro.count(Step.Type.SIGN) == 1, "ожидалась 1 табличка");
        check(macro.count(Step.Type.BREAK) == 1, "ожидался 1 слом");
        check(macro.steps.stream().anyMatch(s -> s.type == Step.Type.USE && s.placed), "ожидалась установка блока");

        // текст шаблоном с номером
        macro.steps.stream().filter(s -> s.type == Step.Type.SIGN).forEach(s -> {
            s.lines[0] = "Дом";
            s.lines[1] = "номер {n}";
        });

        // ---- откат мира для проверки повтора
        srv.runCommand("setblock " + fmt(base.add(2, 1, 0)) + " air");
        fill(srv, base.add(3, -1, -2), base.add(3, 3, 2), "stone");
        srv.runCommand("gamemode survival @a");
        ctx.waitTicks(5);

        // ---- цели: три подряд вдоль стены и одна на северной стене с поворотом
        ctx.runOnClient(mc -> {
            Studio st = Studio.get();
            st.select(macro);
            st.targets.clear();
            Target first = new Target(base.add(3, 1, -2), 0);
            st.targets.add(first);
            st.addSeries(2, 0, 0, 2);
            st.targets.add(new Target(base.add(0, 1, -4), 3));
            mc.player.setYaw(-90f);
            mc.player.setPitch(15f);
        });
        ctx.waitTicks(5);
        ctx.takeScreenshot("10-preview");
        ctx.runOnClient(mc -> Studio.get().runMacro());
        ctx.waitTicks(40);
        ctx.takeScreenshot("11-replay");
        ctx.waitFor(mc -> !Studio.get().tasks.busy(), 3000);
        var t = Studio.get().tasks.last();
        check(t != null && t.isDone() && !t.isFailed(), "повтор не удался: " + (t == null ? "" : t.result()));
        ctx.waitTicks(10);
        ctx.takeScreenshot("12-after");

        int[][] expectedSigns = {{2, 1, -2, 1}, {2, 1, 0, 2}, {2, 1, 2, 3}, {0, 1, -3, 4}};
        for (int[] e : expectedSigns) {
            BlockPos p = base.add(e[0], e[1], e[2]);
            String text = ctx.computeOnClient(mc -> {
                BlockEntity be = mc.world.getBlockEntity(p);
                if (be instanceof SignBlockEntity sbe) return sbe.getText(true).getMessage(0, false).getString() + "/" + sbe.getText(true).getMessage(1, false).getString();
                return "НЕТ ТАБЛИЧКИ (" + mc.world.getBlockState(p) + ")";
            });
            System.out.println("[E2E] sign at " + p + ": " + text);
            check(text.equals("Дом/номер " + e[3]), "табличка " + p + ": " + text);
        }
    }
}

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
            if (dia < 1) {
                String dump = ctx.computeOnClient(mc -> {
                    StringBuilder sb = new StringBuilder("игрок " + mc.player.getBlockPos() + "; предметы: ");
                    for (var e : mc.world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, mc.player.getBoundingBox().expand(20), x -> true)) {
                        sb.append(e.getStack()).append("@").append(e.getBlockPos()).append(" ");
                    }
                    return sb.toString();
                });
                System.out.println("[E2E] DUMP " + dump);
                String sdump = srv.computeOnServer(sv -> {
                    StringBuilder sb = new StringBuilder();
                    var sw = sv.getOverworld();
                    var sp = sv.getPlayerManager().getPlayerList().get(0);
                    sb.append("server player ").append(sp.getEntityPos()).append(" inv-empty-slot=").append(sp.getInventory().getEmptySlot()).append("; ");
                    for (var e : sw.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, sp.getBoundingBox().expand(20), x -> true)) {
                        sb.append(e.getStack()).append(" at ").append(e.getEntityPos()).append(" delay=").append(e.cannotPickup()).append(" ");
                    }
                    return sb.toString();
                });
                System.out.println("[E2E] SERVER DUMP " + sdump);
            }
            check(dia >= 1, "нет алмаза: " + dia);

            // ---------- высокое дерево (нужен столб из блоков)
            testTree(ctx, srv, base);

            // ---------- клик мышью по меню
            testMouse(ctx);

            // ---------- новые возможности
            testFeatures(ctx, srv, base);

            // ---------- перенастройка клавиши меню
            testRebind(ctx);

            // ---------- запись и повтор
            testMacro(ctx, srv, base);
            ctx.takeScreenshot("99-end");
        }
    }

    private void testTree(ClientGameTestContext ctx, TestServerContext srv, BlockPos base) {
        BlockPos trunk = base.add(0, 0, 12);
        srv.runCommand("tp @a " + fmt(base.add(0, 0, 8)));
        fill(srv, trunk, trunk.add(0, 6, 0), "minecraft:oak_log");
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:iron_axe");
        srv.runCommand("give @a minecraft:dirt 16");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> Studio.get().startPreset(PresetStore.find("wood"), 7, 20));
        waitTaskEnd(ctx, 3600, "Рубка дерева");
        int logs = ctx.computeOnClient(mc -> mc.player.getInventory().count(Items.OAK_LOG));
        System.out.println("[E2E] logs: " + logs);
        check(logs >= 7, "срублено брёвен: " + logs);
    }

    private static void tp(TestServerContext srv, BlockPos p) {
        srv.runCommand("tp @a " + p.getX() + " " + p.getY() + " " + p.getZ());
    }

    private void testFeatures(ClientGameTestContext ctx, TestServerContext srv, BlockPos base) {
        var cfg = dev.baritonestudio.config.ModConfig.get();
        srv.runCommand("gamemode survival @a");
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:diamond_pickaxe");
        srv.runCommand("give @a minecraft:cobblestone 32");
        tp(srv, base);
        ctx.waitTicks(10);

        // ---- обход воды: пруд поперёк пути
        java.util.concurrent.atomic.AtomicBoolean swam = new java.util.concurrent.atomic.AtomicBoolean();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player != null && mc.player.isTouchingWater()) swam.set(true);
        });
        fill(srv, base.add(3, -1, -3), base.add(4, -1, 3), "water");
        ctx.waitTicks(20);
        BlockPos far = base.add(9, 0, 0);
        ctx.runOnClient(mc -> Studio.get().startGoto(far.getX(), far.getY(), far.getZ()));
        waitTaskEnd(ctx, 900, "Обход воды");
        check(!swam.get(), "бот зашёл в воду при включённом обходе");
        System.out.println("[E2E] water avoided ok");
        tp(srv, base);
        ctx.waitTicks(10);

        // ---- Human-Mode: добыча
        BlockPos hm = base.add(-9, 0, 5);
        fill(srv, hm, hm.add(2, 1, 2), "stone");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            dev.baritonestudio.config.ModConfig.get().humanMode = true;
            Studio.get().startPreset(PresetStore.find("stone"), 3, 16);
        });
        waitTaskEnd(ctx, 1800, "Human-добыча");
        ctx.runOnClient(mc -> dev.baritonestudio.config.ModConfig.get().humanMode = false);
        System.out.println("[E2E] human mine ok");

        // ---- legitMine: руда внутри камня не берётся
        BlockPos lm = base.add(-9, 0, -8);
        fill(srv, lm, lm.add(2, 2, 2), "stone");
        srv.runCommand("setblock " + fmt(lm.add(1, 1, 1)) + " minecraft:diamond_ore");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            dev.baritonestudio.config.ModConfig.get().legitMine = true;
            Studio.get().startPreset(PresetStore.find("diamond"), 1, 12);
        });
        ctx.waitFor(mc -> !Studio.get().tasks.busy(), 200);
        var lt = Studio.get().tasks.last();
        ctx.runOnClient(mc -> dev.baritonestudio.config.ModConfig.get().legitMine = false);
        check(lt.isFailed(), "legitMine должен не находить скрытую руду: " + lt.result());
        System.out.println("[E2E] legitMine ok: " + lt.result());

        // ---- следовать за существом
        tp(srv, base);
        srv.runCommand("summon minecraft:pig " + (base.getX() + 14) + " " + base.getY() + " " + base.getZ() + " {NoAI:1b}");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> Studio.get().startFollow("pig", 3));
        ctx.waitFor(mc -> {
            for (var e : mc.world.getEntitiesByClass(net.minecraft.entity.passive.PigEntity.class, mc.player.getBoundingBox().expand(20), x -> true)) {
                if (e.distanceTo(mc.player) < 5.5) return true;
            }
            return false;
        }, 700);
        ctx.runOnClient(mc -> Studio.get().stopAll(null));
        srv.runCommand("kill @e[type=minecraft:pig]");
        System.out.println("[E2E] follow ok");

        // ---- исследование
        tp(srv, base);
        ctx.waitTicks(10);
        BlockPos startPos = ctx.computeOnClient(mc -> mc.player.getBlockPos());
        ctx.runOnClient(mc -> Studio.get().startExplore());
        ctx.waitTicks(500);
        BlockPos endPos = ctx.computeOnClient(mc -> mc.player.getBlockPos());
        ctx.runOnClient(mc -> Studio.get().stopAll(null));
        double moved = Math.sqrt(startPos.getSquaredDistance(endPos));
        System.out.println("[E2E] explore moved " + moved);
        check(moved > 25, "исследование почти не сдвинулось: " + moved);

        // ---- метки
        tp(srv, base);
        ctx.waitTicks(10);
        BlockPos wpPos = base.add(-4, 0, -12);
        ctx.runOnClient(mc -> dev.baritonestudio.preset.Waypoints.set("e2e-wp", wpPos));
        ctx.runOnClient(mc -> Studio.get().gotoWaypoint(dev.baritonestudio.preset.Waypoints.find("e2e-wp")));
        waitTaskEnd(ctx, 900, "Переход к метке");
        BlockPos atWp = ctx.computeOnClient(mc -> mc.player.getBlockPos());
        check(atWp.getSquaredDistance(wpPos) <= 2, "не дошёл до метки: " + atWp);
        System.out.println("[E2E] waypoint ok");

        // ---- на поверхность из закрытой комнаты
        BlockPos room = base.add(-20, 0, 0);
        fill(srv, room.add(-3, 0, -3), room.add(3, 4, 3), "stone");
        fill(srv, room.add(-2, 0, -2), room.add(2, 3, 2), "air");
        tp(srv, room);
        ctx.waitTicks(15);
        ctx.runOnClient(mc -> Studio.get().startSurface());
        waitTaskEnd(ctx, 1500, "Выход на поверхность");
        BlockPos sp = ctx.computeOnClient(mc -> mc.player.getBlockPos());
        boolean outside = Math.abs(sp.getX() - room.getX()) > 3 || Math.abs(sp.getZ() - room.getZ()) > 3 || sp.getY() > room.getY() + 4;
        check(outside, "не вышел из комнаты: " + sp);
        System.out.println("[E2E] surface ok at " + sp);

        // ---- очистка области
        BlockPos ca = base.add(8, 0, 8);
        fill(srv, ca, ca.add(2, 1, 2), "stone");
        tp(srv, base.add(5, 0, 5));
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            Studio st = Studio.get();
            st.selA = ca;
            st.selB = ca.add(2, 1, 2);
            st.startClearArea();
        });
        waitTaskEnd(ctx, 2400, "Очистка области");
        boolean cleared = ctx.computeOnClient(mc -> {
            for (BlockPos p : BlockPos.iterate(ca, ca.add(2, 1, 2))) if (!mc.world.getBlockState(p).isAir()) return false;
            return true;
        });
        check(cleared, "область не очищена");
        System.out.println("[E2E] clear area ok");

        // ---- заполнение области (плита 3x3 на уровне земли)
        BlockPos fa = base.add(8, 0, -8);
        fill(srv, fa, fa.add(2, 0, 2), "air");
        tp(srv, base.add(5, 0, -5));
        srv.runCommand("give @a minecraft:cobblestone 32");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            Studio st = Studio.get();
            st.selA = fa;
            st.selB = fa.add(2, 0, 2);
            st.startFillArea(Items.COBBLESTONE);
        });
        waitTaskEnd(ctx, 2400, "Заполнение области");
        boolean filled = ctx.computeOnClient(mc -> {
            for (BlockPos p : BlockPos.iterate(fa, fa.add(2, 0, 2))) if (mc.world.getBlockState(p).isAir()) return false;
            return true;
        });
        check(filled, "область не заполнена");
        System.out.println("[E2E] fill area ok");

        // ---- выйти, пока не увидели
        tp(srv, base);
        ctx.waitTicks(10);
        java.util.concurrent.atomic.AtomicReference<String> left = new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var c = dev.baritonestudio.config.ModConfig.get();
            c.leaveOnPlayer = true;
            c.leaveDistance = 69;
            c.leaveWhitelist = "Intruder";
            Studio.get().leaveHandler = left::set;
            var fake = new net.minecraft.client.network.OtherClientPlayerEntity(mc.world,
                    new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("Intruder".getBytes()), "Intruder"));
            fake.setPosition(mc.player.getX() + 60, mc.player.getY(), mc.player.getZ());
            mc.world.addEntity(fake);
            Studio.get().startGoto(base.getX(), base.getY(), base.getZ() + 30);
        });
        ctx.waitTicks(20);
        check(left.get() == null, "друг из белого списка не должен вызывать выход");
        ctx.runOnClient(mc -> dev.baritonestudio.config.ModConfig.get().leaveWhitelist = "");
        ctx.waitTicks(10);
        System.out.println("[E2E] leave reason: " + left.get());
        check(left.get() != null && left.get().contains("Intruder"), "выход не сработал");
        ctx.runOnClient(mc -> {
            var c = dev.baritonestudio.config.ModConfig.get();
            c.leaveOnPlayer = false;
            Studio.get().resetLeave();
            for (var e : new java.util.ArrayList<>(mc.world.getPlayers())) if (e != mc.player) e.discard();
            Studio.get().leaveHandler = r -> {};
        });
        check(!ctx.computeOnClient(mc -> Studio.get().tasks.busy()), "задача должна быть остановлена выходом");

        // ---- автоеда
        tp(srv, base);
        srv.runCommand("give @a minecraft:bread 8");
        srv.runOnServer(sv -> sv.getPlayerManager().getPlayerList().get(0).getHungerManager().setFoodLevel(6));
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> Studio.get().startGoto(base.getX() + 6, base.getY(), base.getZ() + 6));
        ctx.waitTicks(200);
        int food = ctx.computeOnClient(mc -> mc.player.getHungerManager().getFoodLevel());
        System.out.println("[E2E] food after: " + food);
        check(food > 8, "автоеда не сработала: " + food);
        ctx.runOnClient(mc -> Studio.get().stopAll(null));
    }

    private void testRebind(ClientGameTestContext ctx) {
        ctx.getInput().pressKey(Keys.OPEN);
        ctx.waitFor(mc -> mc.currentScreen instanceof StudioScreen);
        ctx.runOnClient(mc -> {
            try {
                var f = StudioScreen.class.getDeclaredField("capturing");
                f.setAccessible(true);
                f.set(mc.currentScreen, Keys.OPEN);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_G);
        ctx.waitTicks(2);
        String key = ctx.computeOnClient(mc -> Keys.OPEN.getBoundKeyTranslationKey());
        System.out.println("[E2E] menu key after rebind: " + key);
        check(key.equals("key.keyboard.g"), "клавиша не перенастроилась: " + key);
        // новая клавиша закрывает меню
        ctx.getInput().pressKey(Keys.OPEN);
        ctx.waitFor(mc -> mc.currentScreen == null);
        // и открывает его снова
        ctx.getInput().pressKey(Keys.OPEN);
        ctx.waitFor(mc -> mc.currentScreen instanceof StudioScreen);
        // возвращаем B
        ctx.runOnClient(mc -> {
            Keys.OPEN.setBoundKey(Keys.OPEN.getDefaultKey());
            net.minecraft.client.option.KeyBinding.updateKeysByCode();
        });
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_B);
        ctx.waitFor(mc -> mc.currentScreen == null);
        String back = ctx.computeOnClient(mc -> Keys.OPEN.getBoundKeyTranslationKey());
        check(back.equals("key.keyboard.b"), "B не вернулась: " + back);
    }

    private void testMouse(ClientGameTestContext ctx) {
        ctx.getInput().pressKey(Keys.OPEN);
        ctx.waitFor(mc -> mc.currentScreen instanceof StudioScreen);
        ctx.waitTicks(3);
        // вторая вкладка («Запись»)
        double[] pos = ctx.computeOnClient(mc -> {
            try {
                var scr = mc.currentScreen;
                var fx = StudioScreen.class.getDeclaredField("px");
                var fy = StudioScreen.class.getDeclaredField("py");
                fx.setAccessible(true);
                fy.setAccessible(true);
                double scale = mc.getWindow().getScaleFactor();
                return new double[]{((int) fx.get(scr) + 30) * scale, ((int) fy.get(scr) + 36 + 26 + 10) * scale};
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.getInput().setCursorPos(pos[0], pos[1]);
        ctx.waitTicks(2);
        ctx.getInput().pressMouse(0);
        ctx.waitTicks(3);
        ctx.takeScreenshot("03-clicked-recorder-tab");
        // вернём вкладку, закроем
        StudioScreen.openTab(StudioScreen.TabId.PRESETS);
        ctx.getInput().pressKey(Keys.OPEN);
        ctx.waitFor(mc -> mc.currentScreen == null);
    }

    private void testMacro(ClientGameTestContext ctx, TestServerContext srv, BlockPos base) {
        ctx.runOnClient(mc -> dev.baritonestudio.config.ModConfig.get().humanMode = true);
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
        });
        srv.runCommand("tp @a " + fmt(base.add(-7, 4, 0)) + " -90 22");
        ctx.waitTicks(8);
        ctx.takeScreenshot("10-preview");
        srv.runCommand("tp @a " + fmt(base) + " -90 0");
        ctx.waitTicks(5);
        // вкладка «Запись» с выбранной записью и целями
        ctx.getInput().pressKey(Keys.OPEN);
        ctx.waitFor(mc -> mc.currentScreen instanceof StudioScreen);
        for (int sub = 0; sub < 3; sub++) {
            int fsub = sub;
            ctx.runOnClient(mc -> ((StudioScreen) mc.currentScreen).openRecorderSub(fsub));
            ctx.waitTicks(3);
            ctx.takeScreenshot("05-recorder-sub" + sub);
        }
        ctx.runOnClient(mc -> ((StudioScreen) mc.currentScreen).openPresetEditor());
        ctx.waitTicks(3);
        ctx.takeScreenshot("06-preset-editor");
        // типичное окно 1280x720
        ctx.getInput().resizeWindow(1280, 720);
        ctx.waitTicks(5);
        for (int sub = 0; sub < 3; sub++) {
            int fsub = sub;
            ctx.runOnClient(mc -> ((StudioScreen) mc.currentScreen).openRecorderSub(fsub));
            ctx.waitTicks(3);
            ctx.takeScreenshot("07-wide-recorder-sub" + sub);
        }
        StudioScreen.openTab(StudioScreen.TabId.SETTINGS);
        ctx.waitTicks(3);
        ctx.takeScreenshot("08-wide-settings");
        StudioScreen.openTab(StudioScreen.TabId.PRESETS);
        ctx.waitTicks(3);
        ctx.takeScreenshot("08-wide-presets");
        ctx.getInput().resizeWindow(854, 480);
        ctx.waitTicks(5);
        ctx.getInput().pressKey(Keys.OPEN);
        ctx.waitFor(mc -> mc.currentScreen == null);
        ctx.runOnClient(mc -> Studio.get().runMacro());
        ctx.waitTicks(40);
        ctx.takeScreenshot("11-replay");
        ctx.waitFor(mc -> !Studio.get().tasks.busy(), 3000);
        var t = Studio.get().tasks.last();
        check(t != null && t.isDone() && !t.isFailed(), "повтор не удался: " + (t == null ? "" : t.result()));
        ctx.waitTicks(10);
        ctx.takeScreenshot("12-after");

        // ---- нет нужного предмета: ждёт и корректно останавливается
        srv.runCommand("clear @a minecraft:oak_sign");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            dev.baritonestudio.config.ModConfig.get().itemWaitSeconds = 2;
            Studio st = Studio.get();
            st.targets.clear();
            st.targets.add(new Target(base.add(3, 1, 1), 0));
            st.runMacro();
        });
        ctx.waitFor(mc -> !Studio.get().tasks.busy(), 900);
        var lt = Studio.get().tasks.last();
        System.out.println("[E2E] no-item result: failed=" + lt.isFailed() + " " + lt.result());
        check(lt.isFailed(), "без предмета повтор должен остановиться с ошибкой");
        ctx.runOnClient(mc -> dev.baritonestudio.config.ModConfig.get().itemWaitSeconds = 30);

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

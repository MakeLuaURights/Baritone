package dev.baritonestudio;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.baritonestudio.config.ModConfig;
import dev.baritonestudio.gui.Hud;
import dev.baritonestudio.preset.Preset;
import dev.baritonestudio.preset.PresetStore;
import dev.baritonestudio.util.L;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.util.ActionResult;

public class BaritoneStudio implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModConfig.load();
        PresetStore.load();
        Keys.init();
        Hud.register();

        Studio studio = Studio.get();

        ClientTickEvents.END_CLIENT_TICK.register(client -> studio.tick());

        // ---- запись действий
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() && studio.recorder.active() && player == net.minecraft.client.MinecraftClient.getInstance().player) {
                studio.recorder.onUse(hit, hand);
            }
            return ActionResult.PASS;
        });
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> studio.recorder.onBreak(pos, state));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractSignEditScreen sign && studio.recorder.active()) {
                ScreenEvents.remove(screen).register(sc -> studio.recorder.onSignClosed(sign));
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> studio.onDisconnect());

        // ---- команды: /bs ...
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            var root = ClientCommandManager.literal("bs");
            root.then(ClientCommandManager.literal("stop").executes(ctx -> {
                studio.stopAll(L.t("msg.stopped"));
                return 1;
            }));
            root.then(ClientCommandManager.literal("goto")
                    .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                                        studio.startGoto(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
                                        return 1;
                                    })))));
            root.then(ClientCommandManager.literal("preset")
                    .then(ClientCommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                for (Preset p : PresetStore.all()) b.suggest(p.id);
                                return b.buildFuture();
                            })
                            .executes(ctx -> {
                                Preset p = PresetStore.find(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id"));
                                if (p == null) {
                                    studio.say(net.minecraft.util.Formatting.RED, L.t("msg.no_such_preset"));
                                    return 0;
                                }
                                studio.startPreset(p, null, null);
                                return 1;
                            })));
            root.then(ClientCommandManager.literal("follow")
                    .executes(ctx -> {
                        studio.startFollow("", 3);
                        return 1;
                    })
                    .then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                        studio.startFollow(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"), 3);
                        return 1;
                    })));
            root.then(ClientCommandManager.literal("explore").executes(ctx -> {
                studio.startExplore();
                return 1;
            }));
            root.then(ClientCommandManager.literal("surface").executes(ctx -> {
                studio.startSurface();
                return 1;
            }));
            root.then(ClientCommandManager.literal("wp")
                    .then(ClientCommandManager.literal("set").then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                        var mc = net.minecraft.client.MinecraftClient.getInstance();
                        dev.baritonestudio.preset.Waypoints.set(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"), mc.player.getBlockPos());
                        studio.say(net.minecraft.util.Formatting.GREEN, L.t("msg.wp_saved"));
                        return 1;
                    })))
                    .then(ClientCommandManager.literal("goto").then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                        var w = dev.baritonestudio.preset.Waypoints.find(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"));
                        if (w == null) {
                            studio.say(net.minecraft.util.Formatting.RED, L.t("msg.wp_none"));
                            return 0;
                        }
                        studio.gotoWaypoint(w);
                        return 1;
                    })))
                    .then(ClientCommandManager.literal("del").then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                        var w = dev.baritonestudio.preset.Waypoints.find(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"));
                        if (w != null) dev.baritonestudio.preset.Waypoints.remove(w);
                        return 1;
                    })))
                    .then(ClientCommandManager.literal("list").executes(ctx -> {
                        for (var w : dev.baritonestudio.preset.Waypoints.all()) {
                            studio.say(net.minecraft.util.Formatting.AQUA, w.name + ": " + w.x + " " + w.y + " " + w.z);
                        }
                        return 1;
                    })));
            root.then(ClientCommandManager.literal("learn").executes(ctx -> {
                if (dev.baritonestudio.human.HumanLearner.active()) dev.baritonestudio.human.HumanLearner.stop();
                else dev.baritonestudio.human.HumanLearner.start();
                return 1;
            }));
            root.then(ClientCommandManager.literal("record").executes(ctx -> {
                if (studio.recorder.active()) studio.stopRecording(studio.nextMacroName());
                else studio.startRecording();
                return 1;
            }));
            root.then(ClientCommandManager.literal("run").executes(ctx -> {
                studio.runMacro();
                return 1;
            }));
            dispatcher.register(root);
        });
    }
}

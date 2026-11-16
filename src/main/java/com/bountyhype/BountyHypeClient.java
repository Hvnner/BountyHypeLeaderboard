package com.bountyhype;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BountyHype - client-side only (Fabric, Mojang mappings, MC 1.21.10).
 * Watches chat for "X added $N to your bounty" style messages and plays tiered hype alerts.
 */
public class BountyHypeClient implements ClientModInitializer {

    // ====== TWEAK THESE ======
    static final double TIER1 = 1_000_000d;      // quiet chime + small banner
    static final double TIER2 = 10_000_000d;     // louder sound + big gold banner
    static final double TIER3 = 100_000_000d;    // galaxy
    static final double TIER_UFO = 500_000_000d; // ufo flies in, shakes, explodes
    static final double TIER4 = 1_000_000_000d;  // nuke

    // Matches e.g. "Steve add $ 1M to your bounty." (also "added", no $, etc.)
    // Also: "Steve added 1m to your bounty", "Steve added 2.5b to your bounty", "Steve added $1,000,000 to your bounty"
    // Group 1 = name, 2 = number, 3 = optional k/m/b/t suffix.
    static final Pattern BOUNTY_PATTERN = Pattern.compile(
        "(\\w{3,16})\\s+add(?:s|ed)?\\s+\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kmbt]?)\\s+to\\s+your\\s+bounty",
        Pattern.CASE_INSENSITIVE);
    // ==========================

    static boolean debug = false;
    static final ArrayDeque<Alert> queue = new ArrayDeque<>();
    static Alert current = null;

    record Cue(long ms, SoundEvent sound, float pitch, float vol) {}

    static class Alert {
        final String name; final double amount; final int tier;
        long start = -1; int nextCue = 0; Cue[] cues; long duration; int variant = 0;
        /** Builds an alert; variant 0 = original animation, 1/2 = rare extra animations. */
        static Alert make(String name, double amount, int variant) {
            Alert a = new Alert(name, amount);
            if (variant <= 0) return a;
            List<Cue> c = new ArrayList<>();
            switch (a.tier * 10 + variant) {
                case 11 -> { // coin shower
                    a.duration = 4500;
                    for (int i = 0; i < 16; i++) c.add(q(i * 160L, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f + i * 0.06f, 0.5f));
                    c.add(q(2600, SoundEvents.PLAYER_LEVELUP, 1.0f, 0.6f));
                }
                case 12 -> { // confetti cannons
                    a.duration = 5000;
                    c.add(q(0, SoundEvents.FIREWORK_ROCKET_BLAST, 1.2f, 0.9f));
                    c.add(q(250, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f, 0.5f));
                    c.add(q(900, SoundEvents.FIREWORK_ROCKET_BLAST, 1.4f, 0.9f));
                    c.add(q(1150, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.8f, 0.5f));
                    c.add(q(1500, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.7f));
                }
                case 21 -> { // firework show
                    a.duration = 6500;
                    c.add(q(0, SoundEvents.PLAYER_LEVELUP, 1.0f, 0.8f));
                    for (int i = 0; i < 6; i++) {
                        long s0 = 300 + i * 750L;
                        c.add(q(s0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.0f, 0.8f));
                        c.add(q(s0 + 600, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.9f + i * 0.05f, 1.0f));
                        c.add(q(s0 + 650, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f));
                    }
                }
                case 22 -> { // lightning strike
                    a.duration = 5500;
                    c.add(q(300, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0f, 1.5f));
                    c.add(q(1100, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.9f, 1.5f));
                    c.add(q(1900, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8f, 2.0f));
                    c.add(q(2600, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7f, 2.0f));
                    c.add(q(3200, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 3.0f));
                    c.add(q(3200, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1.0f));
                    c.add(q(3700, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f));
                }
                case 31 -> { // supernova
                    a.duration = 9500;
                    c.add(q(0, SoundEvents.BEACON_ACTIVATE, 0.7f, 1.0f));
                    c.add(q(1500, SoundEvents.CONDUIT_ACTIVATE, 1.0f, 1.0f));
                    c.add(q(2400, SoundEvents.TNT_PRIMED, 1.5f, 1.0f));
                    c.add(q(2700, SoundEvents.TNT_PRIMED, 1.8f, 1.0f));
                    c.add(q(3000, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f, 3.0f));
                    c.add(q(3000, SoundEvents.WITHER_SPAWN, 0.7f, 1.0f));
                    c.add(q(3000, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.6f, 1.0f));
                    c.add(q(3400, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1.0f));
                    c.add(q(3600, SoundEvents.ENDER_DRAGON_GROWL, 1.0f, 0.8f));
                    c.add(q(5000, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f));
                }
                case 32 -> { // black hole
                    a.duration = 9500;
                    c.add(q(0, SoundEvents.END_PORTAL_SPAWN, 0.6f, 1.0f));
                    c.add(q(1500, SoundEvents.ENDER_DRAGON_GROWL, 0.5f, 0.8f));
                    c.add(q(2500, SoundEvents.BEACON_AMBIENT, 0.5f, 1.0f));
                    c.add(q(4200, SoundEvents.TNT_PRIMED, 0.6f, 1.0f));
                    c.add(q(5000, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 3.0f));
                    c.add(q(5000, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 1.0f));
                    c.add(q(5050, SoundEvents.WITHER_SPAWN, 0.8f, 1.0f));
                    c.add(q(6500, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.0f));
                }
                case 41 -> { // mothership laser barrage
                    a.duration = 10500;
                    c.add(q(0, SoundEvents.ELDER_GUARDIAN_CURSE, 1.4f, 0.8f));
                    c.add(q(0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.6f, 1.0f));
                    c.add(q(800, SoundEvents.BEACON_AMBIENT, 1.2f, 1.0f));
                    for (int k = 0; k < 12; k++) c.add(q(1500 + k * 260L, SoundEvents.BLAZE_SHOOT, 1.4f + (k % 3) * 0.1f, 0.8f));
                    c.add(q(5200, SoundEvents.TNT_PRIMED, 1.0f, 1.0f));
                    c.add(q(5500, SoundEvents.TNT_PRIMED, 1.4f, 1.0f));
                    c.add(q(5800, SoundEvents.TNT_PRIMED, 1.8f, 1.0f));
                    c.add(q(6000, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7f, 3.0f));
                    c.add(q(6000, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 1.0f));
                    c.add(q(6050, SoundEvents.WITHER_SPAWN, 1.0f, 0.8f));
                    c.add(q(6300, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f));
                    c.add(q(7000, SoundEvents.ENDER_DRAGON_GROWL, 1.2f, 0.8f));
                    c.add(q(7800, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f));
                }
                case 42 -> { // ufo crash landing
                    a.duration = 10500;
                    c.add(q(0, SoundEvents.ELDER_GUARDIAN_CURSE, 1.0f, 0.8f));
                    c.add(q(0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.5f, 1.0f));
                    c.add(q(1500, SoundEvents.TNT_PRIMED, 1.0f, 1.0f));
                    c.add(q(2200, SoundEvents.TNT_PRIMED, 1.2f, 1.0f));
                    c.add(q(2800, SoundEvents.TNT_PRIMED, 1.4f, 1.0f));
                    c.add(q(3300, SoundEvents.TNT_PRIMED, 1.6f, 1.0f));
                    c.add(q(3700, SoundEvents.TNT_PRIMED, 1.8f, 1.0f));
                    c.add(q(4000, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 3.0f));
                    c.add(q(4000, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.6f, 1.0f));
                    c.add(q(4050, SoundEvents.WITHER_SPAWN, 0.7f, 1.0f));
                    c.add(q(4300, SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.9f, 1.0f));
                    c.add(q(5000, SoundEvents.ENDER_DRAGON_GROWL, 0.7f, 0.8f));
                    c.add(q(6000, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f));
                }
                case 51 -> { // meteor strike
                    a.duration = 11500;
                    c.add(q(0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.4f, 1.0f));
                    c.add(q(0, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 0.6f));
                    c.add(q(600, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 0.8f));
                    c.add(q(1200, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f, 1.0f));
                    c.add(q(1800, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f, 1.5f));
                    c.add(q(2600, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.4f, 3.0f));
                    c.add(q(2600, SoundEvents.WITHER_SPAWN, 0.5f, 1.0f));
                    c.add(q(2600, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.5f, 1.0f));
                    c.add(q(2800, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.6f, 1.0f));
                    c.add(q(3000, SoundEvents.ENDER_DRAGON_GROWL, 0.5f, 1.0f));
                    c.add(q(4500, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.0f));
                }
                case 52 -> { // orbital strike
                    a.duration = 11500;
                    c.add(q(0, SoundEvents.BEACON_ACTIVATE, 0.6f, 1.0f));
                    c.add(q(0, SoundEvents.TNT_PRIMED, 1.0f, 1.0f));
                    c.add(q(900, SoundEvents.TNT_PRIMED, 1.3f, 1.0f));
                    c.add(q(1800, SoundEvents.TNT_PRIMED, 1.7f, 1.0f));
                    c.add(q(2700, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.4f, 3.0f));
                    c.add(q(2700, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.5f, 1.0f));
                    c.add(q(2700, SoundEvents.WITHER_SPAWN, 0.6f, 1.0f));
                    c.add(q(2950, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 1.0f));
                    c.add(q(3400, SoundEvents.ENDER_DRAGON_GROWL, 0.5f, 1.0f));
                    c.add(q(4500, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f));
                }
                default -> { return a; }
            }
            a.variant = variant;
            a.cues = sorted(c);
            return a;
        }

        Alert(String name, double amount) {
            this.name = name; this.amount = amount;
            this.tier = amount >= TIER4 ? 5 : amount >= TIER_UFO ? 4 : amount >= TIER3 ? 3 : amount >= TIER2 ? 2 : 1;
            switch (tier) {
                case 1 -> { duration = 3500; cues = new Cue[]{
                    new Cue(0, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f, 0.35f),
                    new Cue(120, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.8f, 0.35f)}; }
                case 2 -> { duration = 5000; cues = new Cue[]{
                    new Cue(0, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f),
                    new Cue(200, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.9f),
                    new Cue(500, SoundEvents.FIREWORK_ROCKET_BLAST, 1.0f, 0.8f)}; }
                case 3 -> { duration = 8000; cues = new Cue[]{
                    new Cue(0, SoundEvents.BEACON_ACTIVATE, 0.8f, 1.0f),
                    new Cue(300, SoundEvents.ENDER_DRAGON_GROWL, 1.6f, 0.6f),
                    new Cue(600, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 1.0f),
                    new Cue(1200, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.0f),
                    new Cue(2000, SoundEvents.FIREWORK_ROCKET_BLAST, 1.2f, 1.0f),
                    new Cue(3000, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f)}; }
                case 4 -> { duration = 10000; cues = new Cue[]{
                    new Cue(0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.6f, 1.0f),
                    new Cue(0, SoundEvents.ELDER_GUARDIAN_CURSE, 1.4f, 0.8f),
                    new Cue(300, SoundEvents.BEACON_AMBIENT, 1.2f, 1.0f),
                    new Cue(1500, SoundEvents.BEACON_AMBIENT, 0.8f, 1.0f),
                    new Cue(2000, SoundEvents.TNT_PRIMED, 1.0f, 0.8f),
                    new Cue(2500, SoundEvents.TNT_PRIMED, 1.2f, 0.9f),
                    new Cue(3000, SoundEvents.TNT_PRIMED, 1.4f, 1.0f),
                    new Cue(3450, SoundEvents.TNT_PRIMED, 1.6f, 1.0f),
                    new Cue(3850, SoundEvents.TNT_PRIMED, 1.8f, 1.0f),
                    new Cue(4300, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8f, 3.0f),
                    new Cue(4300, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 1.0f),
                    new Cue(4400, SoundEvents.WITHER_SPAWN, 1.0f, 0.8f),
                    new Cue(4600, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f),
                    new Cue(5000, SoundEvents.ENDER_DRAGON_GROWL, 1.2f, 0.8f),
                    new Cue(5800, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)}; }
                default -> { duration = 11000; cues = new Cue[]{
                    new Cue(0, SoundEvents.TNT_PRIMED, 0.5f, 1.0f),
                    new Cue(450, SoundEvents.TNT_PRIMED, 0.6f, 1.0f),
                    new Cue(900, SoundEvents.TNT_PRIMED, 0.7f, 1.0f),
                    new Cue(1300, SoundEvents.TNT_PRIMED, 0.9f, 1.0f),
                    new Cue(1700, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 3.0f),
                    new Cue(1700, SoundEvents.WITHER_SPAWN, 0.6f, 1.0f),
                    new Cue(1750, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.5f, 1.0f),
                    new Cue(2500, SoundEvents.ENDER_DRAGON_GROWL, 0.6f, 1.0f),
                    new Cue(4000, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f)}; }
            }
        }
    }

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleChat(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> handleChat(message.getString()));

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
            ResourceLocation.fromNamespaceAndPath("bountyhype", "hype"),
            (g, tick) -> render(g));

        loadBoard();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, reg) -> {
            dispatcher.register(ClientCommandManager.literal("bountyhype")
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debug = !debug;
                    ctx.getSource().sendFeedback(Component.literal("[BountyHype] debug " + (debug ? "ON" : "OFF")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("leaderboard").executes(ctx -> {
                    showBoard(c -> ctx.getSource().sendFeedback(c));
                    return 1;
                }))
                .then(ClientCommandManager.literal("test")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .then(ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> {
                                queue.add(Alert.make(StringArgumentType.getString(ctx, "name"),
                                    DoubleArgumentType.getDouble(ctx, "amount"), pickVariant()));
                                return 1;
                            })
                            .then(ClientCommandManager.argument("variant", IntegerArgumentType.integer(0, 2))
                                .executes(ctx -> {
                                    queue.add(Alert.make(StringArgumentType.getString(ctx, "name"),
                                        DoubleArgumentType.getDouble(ctx, "amount"),
                                        IntegerArgumentType.getInteger(ctx, "variant")));
                                    return 1;
                                }))))));

            dispatcher.register(ClientCommandManager.literal("bounty-hype-leaderboard")
                .executes(ctx -> {
                    showBoard(c -> ctx.getSource().sendFeedback(c));
                    return 1;
                })
                .then(ClientCommandManager.literal("reset").executes(ctx -> {
                    totals.clear();
                    saveBoard();
                    ctx.getSource().sendFeedback(Component.literal("[BountyHype] leaderboard reset"));
                    return 1;
                })));
        });
    }

    static String lastMsg = "";
    static long lastMsgTime = 0;

    static void handleChat(String raw) {
        String msg = raw.replaceAll("§.", "").trim();
        // ignore the exact same line arriving twice within 2s (chat + action bar)
        long nowMs = System.currentTimeMillis();
        if (msg.equals(lastMsg) && nowMs - lastMsgTime < 2000) return;
        lastMsg = msg; lastMsgTime = nowMs;
        Matcher m = BOUNTY_PATTERN.matcher(msg);
        if (!m.find()) {
            if (debug && msg.toLowerCase().contains("bounty")) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.getChat()
                    .addMessage(Component.literal("[BountyHype] no match: " + msg)));
            }
            return;
        }
        double amount = parse(m.group(2), m.group(3));
        addToBoard(m.group(1), amount);
        if (amount >= TIER1) queue.add(Alert.make(m.group(1), amount, pickVariant()));
    }

    static double parse(String num, String suffix) {
        double v = Double.parseDouble(num.replace(",", ""));
        return switch (suffix.toLowerCase()) {
            case "k" -> v * 1_000d;
            case "m" -> v * 1_000_000d;
            case "b" -> v * 1_000_000_000d;
            case "t" -> v * 1_000_000_000_000d;
            default -> v;
        };
    }

    // ================= RENDERING =================

    static int argb(int a, int r, int g, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (r << 16) | (g << 8) | b;
    }

    static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int hw = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - hw, cy + dy, cx + hw, cy + dy + 1, color);
        }
    }

    static void bigText(GuiGraphics g, String text, float x, float y, float scale, int color) {
        Minecraft mc = Minecraft.getInstance();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawCenteredString(mc.font, Component.literal(text), 0, 0, color);
        g.pose().popMatrix();
    }

    static String money(double v) { return String.format("$%,.0f", v); }

    static void render(GuiGraphics g) {
        long now = System.currentTimeMillis();
        if (current == null) {
            current = queue.poll();
            if (current == null) return;
            current.start = now;
        }
        Alert a = current;
        long t = now - a.start;
        if (t > a.duration) { current = null; return; }

        Minecraft mc = Minecraft.getInstance();
        while (a.nextCue < a.cues.length && t >= a.cues[a.nextCue].ms()) {
            Cue c = a.cues[a.nextCue++];
            mc.getSoundManager().play(SimpleSoundInstance.forUI(c.sound(), c.pitch(), c.vol()));
        }

        int w = g.guiWidth(), h = g.guiHeight();
        float fadeOut = (float) Math.min(1.0, (a.duration - t) / 700.0);
        if (a.variant > 0) { rare(g, a, t, w, h, fadeOut); return; }
        switch (a.tier) {
            case 1 -> small(g, a, t, w, fadeOut);
            case 2 -> medium(g, a, t, w, fadeOut);
            case 3 -> galaxy(g, a, t, w, h, fadeOut);
            case 4 -> ufo(g, a, t, w, h, fadeOut);
            default -> nuke(g, a, t, w, h, fadeOut);
        }
    }

    static void small(GuiGraphics g, Alert a, long t, int w, float fade) {
        float slide = (float) Math.min(1.0, t / 250.0);
        int y = (int) (-40 + slide * 60);
        int al = (int) (200 * fade);
        g.fill(w / 2 - 110, y, w / 2 + 110, y + 32, argb(al, 15, 15, 25));
        g.fill(w / 2 - 110, y, w / 2 - 108, y + 32, argb((int) (255 * fade), 80, 220, 120));
        bigText(g, a.name, w / 2f, y + 6, 1.0f, argb((int) (255 * fade), 255, 255, 255));
        bigText(g, "+" + money(a.amount), w / 2f, y + 18, 1.0f, argb((int) (255 * fade), 90, 255, 130));
    }

    static void medium(GuiGraphics g, Alert a, long t, int w, float fade) {
        float slide = (float) Math.min(1.0, t / 250.0);
        int y = (int) (-70 + slide * 100);
        float pulse = 1f + 0.06f * (float) Math.sin(t / 90.0);
        int al = (int) (220 * fade);
        g.fill(w / 2 - 150, y, w / 2 + 150, y + 56, argb(al, 25, 18, 5));
        int gold = argb((int) (255 * fade), 255, 200, 40);
        g.fill(w / 2 - 150, y, w / 2 + 150, y + 2, gold);
        g.fill(w / 2 - 150, y + 54, w / 2 + 150, y + 56, gold);
        bigText(g, a.name, w / 2f, y + 8, 1.6f, argb((int) (255 * fade), 255, 255, 255));
        bigText(g, "+" + money(a.amount), w / 2f, y + 30, 1.9f * pulse, gold);
    }

    static void galaxy(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        float fadeIn = (float) Math.min(1.0, t / 500.0);
        float k = Math.min(fade, fadeIn);
        g.fill(0, 0, w, h, argb((int) (235 * k), 3, 0, 15));

        Random rnd = new Random(42);
        for (int i = 0; i < 160; i++) {
            int x = rnd.nextInt(w), y = rnd.nextInt(h);
            double tw = 0.5 + 0.5 * Math.sin(t / 250.0 + i);
            int s = rnd.nextInt(3) == 0 ? 2 : 1;
            g.fill(x, y, x + s, y + s, argb((int) (255 * k * tw), 255, 255, 255));
        }

        int cx = w / 2, cy = h / 2;
        double maxR = Math.min(w, h) * 0.55 * Math.min(1.0, t / 900.0 + 0.2);
        double spin = t * 0.0006;
        for (int arm = 0; arm < 4; arm++) {
            for (int i = 0; i < 140; i++) {
                double f = i / 140.0;
                double r = f * maxR;
                double ang = arm * Math.PI / 2 + r * 0.035 + spin;
                int x = (int) (cx + Math.cos(ang) * r * 1.6);
                int y = (int) (cy + Math.sin(ang) * r * 0.7);
                int rr = (int) (120 + 135 * f), gg = (int) (60 + 60 * (1 - f)), bb = (int) (255 - 100 * f);
                int s = 1 + (i % 7 == 0 ? 2 : (i % 3 == 0 ? 1 : 0));
                g.fill(x, y, x + s, y + s, argb((int) (230 * k * (1 - f * 0.5)), rr, gg, bb));
            }
        }
        circle(g, cx, cy, 26, argb((int) (60 * k), 200, 150, 255));
        circle(g, cx, cy, 14, argb((int) (110 * k), 255, 220, 255));
        circle(g, cx, cy, 6, argb((int) (255 * k), 255, 255, 255));

        float pulse = 1f + 0.07f * (float) Math.sin(t / 120.0);
        int al = (int) (255 * k);
        bigText(g, "GALACTIC BOUNTY!", cx, cy - 78, 2.0f, argb(al, 190, 150, 255));
        bigText(g, a.name, cx, cy - 50, 3.0f * pulse, argb(al, 255, 255, 255));
        bigText(g, "+" + money(a.amount), cx, cy + 62, 3.6f * pulse, argb(al, 255, 215, 60));
    }


    static void ellipse(GuiGraphics g, int cx, int cy, int rx, int ry, int color) {
        for (int dy = -ry; dy <= ry; dy++) {
            int hw = (int) (rx * Math.sqrt(1.0 - (double) dy * dy / ((double) ry * ry)));
            g.fill(cx - hw, cy + dy, cx + hw, cy + dy + 1, color);
        }
    }

    static void ufoShip(GuiGraphics g, int x, int y, long t, float fade) {
        int al = (int) (255 * fade);
        ellipse(g, x, y + 12, 52, 12, argb(al, 70, 75, 95));
        ellipse(g, x, y, 90, 18, argb(al, 150, 160, 185));
        ellipse(g, x, y - 6, 70, 7, argb(al, 200, 210, 230));
        ellipse(g, x, y - 18, 34, 22, argb((int) (210 * fade), 120, 230, 255));
        ellipse(g, x - 10, y - 26, 10, 7, argb((int) (230 * fade), 230, 255, 255));
        for (int i = 0; i < 7; i++) {
            int lx = x - 72 + i * 24, ly = y + 4;
            int ph = (int) ((t / 110 + i) % 3);
            int c = ph == 0 ? argb(al, 255, 60, 60) : ph == 1 ? argb(al, 255, 230, 60) : argb(al, 80, 255, 120);
            g.fill(lx - 3, ly - 2, lx + 3, ly + 3, c);
        }
    }

    static void ufo(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        Minecraft mc = Minecraft.getInstance();
        int cx = w / 2, cy = h / 2;
        long boom = 4300;

        // whole-scene shake while hovering and right after the blast
        float sx = 0, sy = 0;
        if (t >= 3300 && t < boom) {
            float amp = 4f + 10f * (t - 3300) / 1000f;
            sx = (float) ((Math.random() - 0.5) * amp); sy = (float) ((Math.random() - 0.5) * amp);
        } else if (t >= boom && t < boom + 900) {
            float amp = 16f * (1f - (t - boom) / 900f);
            sx = (float) ((Math.random() - 0.5) * amp); sy = (float) ((Math.random() - 0.5) * amp);
        }
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.fill(-20, -20, w + 20, h + 20, argb((int) (130 * Math.min(1.0, t / 600.0) * fade), 0, 5, 20));

        if (t < boom) {
            double fly = Math.min(1.0, t / 2000.0);
            double ease = 1 - Math.pow(1 - fly, 3);
            int x = (int) (-100 + (cx + 100) * ease);
            int y = (int) (30 + (cy - 30) * ease + (1 - ease) * Math.sin(t / 120.0) * 25);
            int ux = x, uy = y;
            if (t >= 2000) {
                double sh = (t - 2000) / 2300.0;
                double amp = 2 + 16 * sh;
                ux = cx + (int) ((Math.random() - 0.5) * amp);
                uy = cy + (int) ((Math.random() - 0.5) * amp);
            }
            if (t >= 1500) { // tractor beam
                int bh = h - uy;
                for (int i = 0; i < bh; i += 2) {
                    int half = 14 + i / 6;
                    g.fill(ux - half, uy + 14 + i, ux + half, uy + 16 + i, argb((int) (45 * fade), 120, 255, 150));
                }
            }
            ufoShip(g, ux, uy, t, fade);
            if (t < 2000 && (t / 150) % 2 == 0) {
                bigText(g, "UFO INCOMING", cx, 20, 2.0f, argb((int) (255 * fade), 120, 255, 150));
            }
            if (t >= 2000) {
                bigText(g, "!!! WARNING !!!", cx, 20, 2.0f, argb((int) (255 * fade), 255, 70, 70));
            }
            g.pose().popMatrix();
            return;
        }

        long e = t - boom;
        // fireball
        float decay = (float) Math.max(0.0, 1.0 - e / 1800.0);
        int fr = (int) Math.min(150, 25 + e * 0.3);
        circle(g, cx, cy, fr, argb((int) (210 * decay * fade), 255, 90, 15));
        circle(g, cx, cy, (int) (fr * 0.72), argb((int) (230 * decay * fade), 255, 170, 40));
        circle(g, cx, cy, (int) (fr * 0.4), argb((int) (255 * decay * fade), 255, 245, 190));

        // debris
        Random rnd = new Random(7);
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double spd = 0.12 + rnd.nextDouble() * 0.45;
            int size = 3 + rnd.nextInt(4);
            int kind = rnd.nextInt(3);
            double dx = Math.cos(ang) * spd * e;
            double dy = Math.sin(ang) * spd * e + 0.00025 * e * e;
            float da = (float) Math.max(0.0, 1.0 - e / 3500.0) * fade;
            int c = kind == 0 ? argb((int) (255 * da), 150, 160, 185)
                  : kind == 1 ? argb((int) (255 * da), 90, 255, 130) : argb((int) (255 * da), 255, 150, 40);
            g.fill((int) (cx + dx), (int) (cy + dy), (int) (cx + dx) + size, (int) (cy + dy) + size, c);
        }

        // shockwave
        double rad = e * 0.8;
        if (rad < w) {
            int ra = (int) (200 * fade * Math.max(0, 1 - rad / (w * 0.9)));
            for (int i = 0; i < 360; i += 3) {
                double ang = Math.toRadians(i);
                int x = (int) (cx + Math.cos(ang) * rad * 1.3);
                int y = (int) (cy + Math.sin(ang) * rad * 0.8);
                g.fill(x, y, x + 3, y + 3, argb(ra, 200, 255, 220));
            }
        }

        // big name
        if (e > 500) {
            float ta = (float) Math.min(1.0, (e - 500) / 500.0) * fade;
            int tal = (int) (255 * ta);
            float pulse = 1f + 0.05f * (float) Math.sin(t / 100.0);
            float nameScale = Math.min(5.5f, w * 0.9f / Math.max(1, mc.font.width(a.name)));
            bigText(g, "UFO DESTROYED BY", cx, cy - 78, 1.8f, argb(tal, 120, 255, 150));
            bigText(g, a.name, cx + 3, cy - 40 + 3, nameScale * pulse, argb(tal, 0, 40, 10));
            bigText(g, a.name, cx, cy - 40, nameScale * pulse, argb(tal, 255, 255, 255));
            bigText(g, "+" + money(a.amount), cx, cy + 40, 3.2f * pulse, argb(tal, 255, 215, 60));
        }
        g.pose().popMatrix();

        if (e < 700) {
            g.fill(0, 0, w, h, argb((int) (255 * (1 - e / 700.0)), 255, 255, 255));
        }
    }


    // ================= LEADERBOARD =================

    static final Map<String, Double> totals = new ConcurrentHashMap<>();

    static Path boardFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("bountyhype-leaderboard.txt");
    }

    static void loadBoard() {
        try {
            Path p = boardFile();
            if (!Files.exists(p)) return;
            for (String line : Files.readAllLines(p)) {
                int i = line.lastIndexOf('=');
                if (i > 0) totals.put(line.substring(0, i), Double.parseDouble(line.substring(i + 1)));
            }
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    static void saveBoard() {
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Double> en : totals.entrySet()) lines.add(en.getKey() + "=" + en.getValue());
            Files.write(boardFile(), lines);
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    static void addToBoard(String name, double amount) {
        totals.merge(name, amount, Double::sum);
        saveBoard();
    }

    static String shortMoney(double v) {
        double x; String suf;
        if (v >= 1e12) { x = v / 1e12; suf = "T"; }
        else if (v >= 1e9) { x = v / 1e9; suf = "B"; }
        else if (v >= 1e6) { x = v / 1e6; suf = "M"; }
        else if (v >= 1e3) { x = v / 1e3; suf = "K"; }
        else { x = v; suf = ""; }
        String s = String.format("%.2f", x);
        if (s.endsWith(".00")) s = s.substring(0, s.length() - 3);
        else if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return "$" + s + suf;
    }

    static void showBoard(java.util.function.Consumer<Component> out) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(totals.entrySet());
        list.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        out.accept(Component.literal("\u00a76\u00a7l=== Bounty Leaderboard ==="));
        if (list.isEmpty()) { out.accept(Component.literal("\u00a77No donations recorded yet.")); return; }
        String[] medal = {"\u00a76", "\u00a77", "\u00a7c"};
        double total = 0;
        for (Map.Entry<String, Double> en : list) total += en.getValue();
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Map.Entry<String, Double> en = list.get(i);
            String col = i < 3 ? medal[i] : "\u00a7f";
            out.accept(Component.literal(col + "#" + (i + 1) + " \u00a7f" + en.getKey()
                + " \u00a78- \u00a7a" + shortMoney(en.getValue()) + " \u00a78(" + money(en.getValue()) + ")"));
        }
        out.accept(Component.literal("\u00a77Total donated: \u00a7a" + shortMoney(total)
            + " \u00a77from " + list.size() + " donor" + (list.size() == 1 ? "" : "s")));
    }

    // ================= RARE ANIMATIONS =================

    static int pickVariant() {
        double r = Math.random();
        return r < 0.70 ? 0 : r < 0.85 ? 1 : 2;   // 70% original, 15% rare A, 15% rare B
    }

    static Cue q(long ms, SoundEvent s, float p, float v) { return new Cue(ms, s, p, v); }

    static Cue[] sorted(List<Cue> l) {
        l.sort(Comparator.comparingLong(Cue::ms));
        return l.toArray(new Cue[0]);
    }

    static final int[][] FIRE = {{255, 90, 15}, {255, 170, 40}, {255, 245, 190}, {255, 60, 30}};
    static final int[][] RAINBOW = {{255, 80, 80}, {255, 200, 50}, {90, 255, 120}, {80, 200, 255}, {200, 100, 255}, {255, 120, 200}};
    static final int[][] SPACE = {{180, 140, 255}, {120, 220, 255}, {255, 255, 255}, {255, 150, 220}};
    static final int[][] DEBRIS = {{150, 160, 185}, {90, 255, 130}, {255, 150, 40}, {255, 230, 150}};
    static final int[][] ROCK = {{255, 120, 30}, {90, 80, 80}, {255, 220, 120}, {60, 50, 50}};

    static void particles(GuiGraphics g, float cx, float cy, long e, long seed, int n, double spdMin, double spdMax,
                          double grav, double life, int[][] pal, float fade, int sizeMax) {
        if (e < 0) return;
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double spd = spdMin + r.nextDouble() * (spdMax - spdMin);
            int sz = 2 + r.nextInt(sizeMax);
            int[] c = pal[r.nextInt(pal.length)];
            double al = 1.0 - e / life;
            if (al <= 0) continue;
            int x = (int) (cx + Math.cos(ang) * spd * e);
            int y = (int) (cy + Math.sin(ang) * spd * e + grav * e * e);
            g.fill(x, y, x + sz, y + sz, argb((int) (255 * al * fade), c[0], c[1], c[2]));
        }
    }

    static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int th, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) steps = 1;
        for (int i = 0; i <= steps; i += 2) {
            int x = x0 + (x1 - x0) * i / steps, y = y0 + (y1 - y0) * i / steps;
            g.fill(x - th, y - th, x + th + 1, y + th + 1, color);
        }
    }

    static void ring(GuiGraphics g, int cx, int cy, double rad, double xs, double ys, int alpha, int r, int gr, int b) {
        if (rad <= 0 || alpha <= 0) return;
        for (int i = 0; i < 360; i += 3) {
            double ang = Math.toRadians(i);
            int x = (int) (cx + Math.cos(ang) * rad * xs), y = (int) (cy + Math.sin(ang) * rad * ys);
            g.fill(x, y, x + 3, y + 3, argb(alpha, r, gr, b));
        }
    }

    static void flash(GuiGraphics g, int w, int h, long e, double dur) {
        if (e >= 0 && e < dur) g.fill(0, 0, w, h, argb((int) (255 * (1 - e / dur)), 255, 255, 255));
    }

    static void shake(GuiGraphics g, float amp) {
        g.pose().pushMatrix();
        g.pose().translate((float) ((Math.random() - 0.5) * amp), (float) ((Math.random() - 0.5) * amp));
    }

    static void unshake(GuiGraphics g) { g.pose().popMatrix(); }

    static void bolt(GuiGraphics g, int x0, int y0, int x1, int y1, long seed, int th, float alpha) {
        Random r = new Random(seed);
        int segs = 16, px = x0, py = y0;
        for (int i = 1; i <= segs; i++) {
            int nx = x0 + (x1 - x0) * i / segs + (i == segs ? 0 : r.nextInt(41) - 20);
            int ny = y0 + (y1 - y0) * i / segs;
            line(g, px, py, nx, ny, th + 3, argb((int) (70 * alpha), 80, 150, 255));
            line(g, px, py, nx, ny, th, argb((int) (255 * alpha), 255, 255, 255));
            px = nx; py = ny;
        }
    }

    static void nameCard(GuiGraphics g, Alert a, int cx, int cy, int w, float ta, long t, String top, int r, int gr, int b) {
        Minecraft mc = Minecraft.getInstance();
        int tal = (int) (255 * Math.max(0f, Math.min(1f, ta)));
        float pulse = 1f + 0.05f * (float) Math.sin(t / 100.0);
        float ns = Math.min(5.5f, w * 0.9f / Math.max(1, mc.font.width(a.name)));
        bigText(g, top, cx, cy - 78, 1.8f, argb(tal, r, gr, b));
        bigText(g, a.name, cx + 3, cy - 40 + 3, ns * pulse, argb(tal, 0, 0, 0));
        bigText(g, a.name, cx, cy - 40, ns * pulse, argb(tal, 255, 255, 255));
        bigText(g, "+" + money(a.amount), cx, cy + 40, 3.2f * pulse, argb(tal, 255, 215, 60));
    }

    static void rare(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        switch (a.tier * 10 + a.variant) {
            case 11 -> coinShower(g, a, t, w, h, fade);
            case 12 -> confetti(g, a, t, w, h, fade);
            case 21 -> fireworkShow(g, a, t, w, h, fade);
            case 22 -> lightning(g, a, t, w, h, fade);
            case 31 -> supernova(g, a, t, w, h, fade);
            case 32 -> blackHole(g, a, t, w, h, fade);
            case 41 -> ufoLaser(g, a, t, w, h, fade);
            case 42 -> ufoCrash(g, a, t, w, h, fade);
            case 51 -> meteor(g, a, t, w, h, fade);
            case 52 -> orbital(g, a, t, w, h, fade);
            default -> { }
        }
    }

    // ---- $1M rare A: coin shower
    static void coinShower(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        Random r = new Random(11);
        for (int i = 0; i < 45; i++) {
            int x = r.nextInt(w);
            long delay = r.nextInt(2000);
            double spd = 0.12 + r.nextDouble() * 0.2;
            if (t < delay) continue;
            int y = (int) (-10 + (t - delay) * spd);
            if (y > h) continue;
            int cw = 2 + (int) (Math.abs(Math.sin((t + i * 37) / 90.0)) * 5);
            g.fill(x, y, x + cw, y + 7, argb((int) (255 * fade), 255, 200, 40));
            g.fill(x, y, x + cw, y + 2, argb((int) (255 * fade), 255, 245, 150));
        }
        small(g, a, t, w, fade);
    }

    // ---- $1M rare B: confetti cannons
    static void confetti(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        for (int k = 0; k < 2; k++) {
            long e = t - k * 900L;
            if (e < 0) continue;
            Random r = new Random(40 + k);
            for (int i = 0; i < 80; i++) {
                boolean left = i % 2 == 0;
                double ang = -1.35 + r.nextDouble() * 0.75;
                if (!left) ang = Math.PI - ang;
                double spd = 0.25 + r.nextDouble() * 0.4;
                int[] c = RAINBOW[r.nextInt(RAINBOW.length)];
                double al = 1 - e / 2800.0;
                if (al <= 0) continue;
                double x0 = left ? 0 : w, y0 = h;
                int x = (int) (x0 + Math.cos(ang) * spd * e);
                int y = (int) (y0 + Math.sin(ang) * spd * e + 0.0004 * e * e);
                int cw = 2 + (int) (Math.abs(Math.sin((t + i * 53) / 70.0)) * 3);
                g.fill(x, y, x + cw, y + 5, argb((int) (255 * al * fade), c[0], c[1], c[2]));
            }
        }
        small(g, a, t, w, fade);
    }

    // ---- $10M rare A: firework show
    static void fireworkShow(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        g.fill(0, 0, w, h, argb((int) (130 * Math.min(1.0, t / 500.0) * fade), 0, 5, 25));
        for (int i = 0; i < 6; i++) {
            long e = t - (300 + i * 750L);
            if (e < 0) continue;
            Random r = new Random(60 + i);
            int tx = (int) (w * (0.15 + 0.7 * r.nextDouble())), ty = (int) (h * (0.3 + 0.35 * r.nextDouble()));
            if (e < 600) {
                double p = e / 600.0;
                int x = (int) (w / 2 + (tx - w / 2) * p), y = (int) (h + (ty - h) * p);
                g.fill(x - 1, y, x + 2, y + 6, argb((int) (255 * fade), 255, 220, 120));
                g.fill(x - 1, y + 6, x + 2, y + 14, argb((int) (120 * fade), 255, 140, 40));
            } else {
                particles(g, tx, ty, e - 600, 500 + i, 50, 0.04, 0.2, 0.00012, 1800, RAINBOW, fade, 3);
            }
        }
        medium(g, a, t, w, fade);
    }

    // ---- $10M rare B: lightning storm
    static void lightning(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2, cy = h / 2;
        double flick = 0.5 + 0.5 * Math.sin(t / 80.0);
        g.fill(0, 0, w, h, argb((int) ((90 + 30 * flick) * fade), 5, 8, 30));
        long[] st = {300, 1100, 1900, 2600, 3200};
        for (int i = 0; i < st.length; i++) {
            long e = t - st[i];
            if (e < 0 || e >= 350) continue;
            float al = 1f - e / 350f;
            boolean fin = i == 4;
            int x0 = fin ? cx : (int) (w * (0.15 + 0.7 * new Random(80 + i).nextDouble()));
            int x1 = fin ? cx : x0 + (new Random(90 + i).nextInt(61) - 30);
            bolt(g, x0, 0, x1, fin ? cy + 30 : (int) (h * 0.7), 70 + i, fin ? 3 : 1, al * fade);
            g.fill(0, 0, w, h, argb((int) ((fin ? 200 : 90) * al * fade), 255, 255, 255));
        }
        if (t >= 3200) {
            long e = t - 3200;
            if (e > 300) nameCard(g, a, cx, cy, w, (float) Math.min(1.0, (e - 300) / 500.0) * fade, t, "LIGHTNING STRIKE!", 120, 190, 255);
        }
    }

    // ---- $100M rare A: supernova
    static void supernova(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2, cy = h / 2;
        float in = (float) Math.min(1.0, t / 500.0);
        g.fill(0, 0, w, h, argb((int) (235 * in * fade), 3, 0, 15));
        Random rs = new Random(42);
        for (int i = 0; i < 160; i++) {
            int x = rs.nextInt(w), y = rs.nextInt(h);
            double tw = 0.5 + 0.5 * Math.sin(t / 250.0 + i);
            g.fill(x, y, x + 1, y + 1, argb((int) (255 * in * tw * fade), 255, 255, 255));
        }
        long boom = 3000;
        if (t < boom) {
            double p = t / 3000.0;
            int r0 = (int) (4 + 14 * p + 2 * Math.sin(t / 60.0));
            circle(g, cx, cy, r0 * 3, argb((int) (60 * fade), 190, 140, 255));
            circle(g, cx, cy, r0 * 2, argb((int) (110 * fade), 220, 190, 255));
            circle(g, cx, cy, r0, argb((int) (255 * fade), 255, 255, 255));
            double maxD = Math.min(w, h) * 0.55;
            for (int i = 0; i < 30; i++) {
                double ang = i * (Math.PI * 2 / 30);
                double d = (1 - ((t + i * 53) % 900) / 900.0) * maxD;
                int x = (int) (cx + Math.cos(ang) * d * 1.5), y = (int) (cy + Math.sin(ang) * d * 0.8);
                g.fill(x, y, x + 2, y + 2, argb((int) (220 * fade), 120, 220, 255));
            }
            if ((t / 200) % 2 == 0) bigText(g, "STAR COLLAPSING...", cx, 20, 2.0f, argb((int) (255 * fade), 190, 150, 255));
            return;
        }
        long e = t - boom;
        shake(g, e < 900 ? 16f * (1f - e / 900f) : 0f);
        float decay = (float) Math.max(0.0, 1.0 - e / 3500.0);
        circle(g, cx, cy, (int) Math.min(220, 20 + e * 0.25), argb((int) (120 * decay * fade), 180, 100, 255));
        circle(g, cx, cy, (int) Math.min(120, 10 + e * 0.18), argb((int) (200 * decay * fade), 255, 200, 255));
        for (int rg = 0; rg < 3; rg++) {
            double rad = Math.max(0, (e - rg * 300) * 0.7);
            if (rad <= 0 || rad > w) continue;
            int ra = (int) (220 * fade * Math.max(0, 1 - rad / (w * 0.9)));
            int[] c = SPACE[rg];
            ring(g, cx, cy, rad, 1.4, 0.8, ra, c[0], c[1], c[2]);
        }
        particles(g, cx, cy, e, 5, 120, 0.05, 0.5, 0.0, 3500, SPACE, fade, 4);
        if (e > 600) nameCard(g, a, cx, cy, w, (float) Math.min(1.0, (e - 600) / 600.0) * fade, t, "SUPERNOVA!", 200, 150, 255);
        unshake(g);
        flash(g, w, h, e, 1000);
    }

    // ---- $100M rare B: black hole
    static void blackHole(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2, cy = h / 2;
        float in = (float) Math.min(1.0, t / 500.0);
        g.fill(0, 0, w, h, argb((int) (240 * in * fade), 0, 0, 6));
        Random rs = new Random(42);
        for (int i = 0; i < 150; i++) {
            int x0 = rs.nextInt(w), y0 = rs.nextInt(h);
            double sc = Math.max(0.04, 1 - (t / 9000.0) * (0.5 + (i % 5) * 0.12));
            int x = (int) (cx + (x0 - cx) * sc), y = (int) (cy + (y0 - cy) * sc);
            double tw = 0.5 + 0.5 * Math.sin(t / 200.0 + i);
            g.fill(x, y, x + 1, y + 1, argb((int) (255 * in * tw * fade), 255, 255, 255));
        }
        long boom = 5000;
        double R = Math.min(w, h) * 0.5;
        float shk = 0f;
        if (t > 4300 && t < 5800) shk = t < 5000 ? 3f + (t - 4300) / 70f : 10f * (1f - (t - 5000) / 800f);
        shake(g, shk);
        for (int i = 0; i < 240; i++) {
            double f = 1 - ((t * 0.0003 + i * 0.0137) % 1.0);
            double r = R * f;
            double ang = i * 2.399 + t * 0.002 * (1.5 + 2 * (1 - f));
            int x = (int) (cx + Math.cos(ang) * r * 1.8), y = (int) (cy + Math.sin(ang) * r * 0.45);
            int[] c = f < 0.3 ? new int[]{255, 240, 200} : f < 0.6 ? new int[]{255, 150, 50} : new int[]{150, 110, 255};
            int s = f < 0.3 ? 3 : 2;
            g.fill(x, y, x + s, y + s, argb((int) (240 * fade * Math.min(1f, in)), c[0], c[1], c[2]));
        }
        int hr = (int) (20 + 8 * Math.min(1.0, t / 5000.0));
        circle(g, cx, cy, hr + 6, argb((int) (120 * fade), 255, 200, 120));
        circle(g, cx, cy, hr, argb((int) (255 * fade), 0, 0, 0));
        if (t < boom) {
            if ((t / 220) % 2 == 0) bigText(g, "SINGULARITY DETECTED...", cx, 20, 2.0f, argb((int) (255 * fade), 180, 140, 255));
        } else {
            long e = t - boom;
            double jf = Math.max(0, 1 - e / 2500.0);
            int jw = (int) ((14 + 10 * Math.sin(e / 40.0)) * jf);
            g.fill(cx - jw * 3, 0, cx + jw * 3, h, argb((int) (60 * jf * fade), 120, 200, 255));
            g.fill(cx - jw, 0, cx + jw, h, argb((int) (200 * jf * fade), 210, 240, 255));
            g.fill(cx - jw / 3, 0, cx + jw / 3, h, argb((int) (255 * jf * fade), 255, 255, 255));
            particles(g, cx, cy, e, 9, 80, 0.04, 0.4, 0.0, 3000, SPACE, fade, 3);
            if (e > 600) nameCard(g, a, cx, cy, w, (float) Math.min(1.0, (e - 600) / 600.0) * fade, t, "EVENT HORIZON!", 190, 140, 255);
        }
        unshake(g);
        if (t >= boom) flash(g, w, h, t - boom, 500);
    }

    // ---- $500M rare A: mothership laser barrage
    static void ufoLaser(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2;
        g.fill(0, 0, w, h, argb((int) (130 * Math.min(1.0, t / 600.0) * fade), 0, 5, 20));
        long boom = 6000;
        int uy0 = (int) (h * 0.22);
        double fly = Math.min(1.0, t / 1500.0);
        double ease = 1 - Math.pow(1 - fly, 3);
        int ux = (int) (-100 + (cx + 100) * ease);
        int uy = (int) (uy0 + (1 - ease) * 40 + Math.sin(t / 200.0) * 4);
        if (t >= 1500 && t < boom) {
            for (int k = 0; k < 12; k++) {
                long e = t - (1500 + k * 260L);
                if (e < 0 || e > 600) continue;
                Random r = new Random(100 + k);
                int tx = (int) (w * (0.08 + 0.84 * r.nextDouble())), ty = (int) (h * (0.78 + 0.15 * r.nextDouble()));
                if (e < 220) {
                    line(g, ux, uy + 16, tx, ty, 3, argb((int) (70 * fade), 255, 60, 60));
                    line(g, ux, uy + 16, tx, ty, 1, argb((int) (255 * fade), 255, 230, 230));
                }
                double ia = Math.max(0, 1 - e / 600.0);
                circle(g, tx, ty, (int) (8 + e * 0.06), argb((int) (230 * ia * fade), 255, 150, 40));
                particles(g, tx, ty, e, 300 + k, 14, 0.03, 0.18, 0.0003, 600, FIRE, fade, 3);
            }
        }
        float amp = 0f;
        if (t >= 5200 && t < boom) amp = 4f + (t - 5200) / 60f;
        else if (t >= boom && t < boom + 900) amp = 16f * (1f - (t - boom) / 900f);
        shake(g, amp);
        if (t < boom) {
            int ox = t >= 5200 ? (int) ((Math.random() - 0.5) * (4 + (t - 5200) / 40.0)) : 0;
            ufoShip(g, ux + ox, uy, t, fade);
            if (t >= 5200 && (t / 100) % 2 == 0) g.fill(0, 0, w, h, argb((int) (50 * fade), 255, 60, 60));
            String msg = t < 1500 ? "UFO INCOMING" : t < 5200 ? "MOTHERSHIP ATTACK!" : "!!! CRITICAL OVERLOAD !!!";
            bigText(g, msg, cx, 6, 2.0f, t < 5200 ? argb((int) (255 * fade), 120, 255, 150) : argb((int) (255 * fade), 255, 70, 70));
        } else {
            long e = t - boom;
            float decay = (float) Math.max(0.0, 1.0 - e / 2200.0);
            int fr = (int) Math.min(150, 25 + e * 0.3);
            circle(g, cx, uy0, fr, argb((int) (210 * decay * fade), 255, 90, 15));
            circle(g, cx, uy0, (int) (fr * 0.7), argb((int) (230 * decay * fade), 255, 170, 40));
            circle(g, cx, uy0, (int) (fr * 0.4), argb((int) (255 * decay * fade), 255, 245, 190));
            particles(g, cx, uy0, e, 7, 60, 0.12, 0.57, 0.00025, 3500, DEBRIS, fade, 4);
            ring(g, cx, uy0, e * 0.8, 1.3, 0.8, (int) (200 * fade * Math.max(0, 1 - e * 0.8 / (w * 0.9))), 200, 255, 220);
            if (e > 500) nameCard(g, a, cx, h / 2, w, (float) Math.min(1.0, (e - 500) / 500.0) * fade, t, "MOTHERSHIP DOWN!", 120, 255, 150);
        }
        unshake(g);
        if (t >= boom) flash(g, w, h, t - boom, 700);
    }

    // ---- $500M rare B: ufo crash landing
    static double[] crashPos(long tt, int w, int h) {
        double p = Math.max(0, Math.min(1, tt / 4000.0));
        double x = (w + 60) + (w / 2.0 - (w + 60)) * p + Math.sin(p * 14) * 40 * (1 - p);
        double y = -40 + (h * 0.78 + 40) * p * p;
        return new double[]{x, y};
    }

    static void ufoCrash(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2, gy = (int) (h * 0.78);
        long boom = 4000;
        g.fill(0, 0, w, h, argb((int) (120 * Math.min(1.0, t / 500.0) * fade), 10, 5, 15));
        float amp = 0f;
        if (t >= 2800 && t < boom) amp = 3f + (t - 2800) / 120f;
        else if (t >= boom && t < boom + 1000) amp = 18f * (1f - (t - boom) / 1000f);
        shake(g, amp);
        g.fill(0, gy + 8, w, h, argb((int) (255 * fade), 25, 20, 30));
        if (t < boom) {
            for (int k = 18; k >= 1; k--) {
                long tk = t - k * 40L;
                if (tk < 0) continue;
                double[] p = crashPos(tk, w, h);
                double al = 1 - k / 19.0;
                int col = k % 3 == 0 ? argb((int) (150 * al * fade), 90, 90, 100) : argb((int) (220 * al * fade), 255, (int) (90 + 120 * al), 20);
                circle(g, (int) p[0], (int) p[1], (int) (14 * al) + 3, col);
            }
            double[] p = crashPos(t, w, h);
            ufoShip(g, (int) p[0], (int) p[1], t, fade);
            circle(g, (int) p[0] - 30, (int) p[1] - 8, 8, argb((int) (220 * fade), 255, 140, 30));
            circle(g, (int) p[0] + 25, (int) p[1] - 12, 6, argb((int) (220 * fade), 255, 220, 60));
            if ((t / 150) % 2 == 0) bigText(g, "!!! UFO DOWN !!!", cx, 6, 2.0f, argb((int) (255 * fade), 255, 90, 60));
        } else {
            long e = t - boom;
            float decay = (float) Math.max(0.0, 1.0 - e / 2500.0);
            int fr = (int) Math.min(170, 20 + e * 0.32);
            circle(g, cx, gy, fr, argb((int) (220 * decay * fade), 255, 90, 15));
            circle(g, cx, gy, (int) (fr * 0.7), argb((int) (235 * decay * fade), 255, 170, 40));
            circle(g, cx, gy, (int) (fr * 0.4), argb((int) (255 * decay * fade), 255, 245, 190));
            int dr = (int) Math.min(w * 0.5, e * 0.35);
            if (dr > 2) ellipse(g, cx, gy + 4, dr, Math.max(2, (int) (dr * 0.18)), argb((int) (120 * Math.max(0, 1 - e / 3500.0) * fade), 120, 110, 100));
            ring(g, cx, gy, e * 0.9, 1.5, 0.3, (int) (200 * fade * Math.max(0, 1 - e * 0.9 / (w * 0.9))), 255, 220, 150);
            particles(g, cx, gy, e, 13, 80, 0.1, 0.55, 0.00028, 3200, DEBRIS, fade, 4);
            if (e > 600) nameCard(g, a, cx, h / 2, w, (float) Math.min(1.0, (e - 600) / 600.0) * fade, t, "CRASH LANDING!", 120, 255, 150);
        }
        unshake(g);
        if (t >= boom) flash(g, w, h, t - boom, 800);
    }

    // ---- $1B rare A: meteor strike
    static double[] meteorPos(long tt, int w, int h) {
        double p = Math.pow(Math.max(0, Math.min(1, tt / 2600.0)), 1.6);
        return new double[]{-40 + (w / 2.0 + 40) * p, -40 + (h * 0.8 + 40) * p};
    }

    static void meteor(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2, gy = (int) (h * 0.8);
        long boom = 2600;
        g.fill(0, 0, w, h, argb((int) (140 * Math.min(1.0, t / 500.0) * fade), 30, 5, 5));
        float amp = 0f;
        if (t >= 1000 && t < boom) amp = 2f + (t - 1000) / 160f;
        else if (t >= boom && t < boom + 1500) amp = 22f * (1f - (t - boom) / 1500f);
        shake(g, amp);
        if (t < boom) {
            for (int k = 30; k >= 1; k--) {
                long tk = t - k * 35L;
                if (tk < 0) continue;
                double[] p = meteorPos(tk, w, h);
                double al = 1 - k / 31.0;
                int rr = (int) (26 * al) + 4;
                int col = k < 10 ? argb((int) (230 * al * fade), 255, (int) (120 + 100 * al), 30) : argb((int) (200 * al * fade), 110, 60, 40);
                circle(g, (int) p[0], (int) p[1], rr, col);
            }
            double[] p = meteorPos(t, w, h);
            int hr = (int) (12 + 24 * Math.min(1.0, t / 2600.0));
            circle(g, (int) p[0], (int) p[1], hr + 6, argb((int) (120 * fade), 255, 140, 30));
            circle(g, (int) p[0], (int) p[1], hr, argb((int) (255 * fade), 255, 200, 90));
            circle(g, (int) p[0], (int) p[1], hr / 2, argb((int) (255 * fade), 255, 255, 255));
            if ((t / 150) % 2 == 0) bigText(g, "!!! METEOR INCOMING !!!", cx, 6, 2.0f, argb((int) (255 * fade), 255, 120, 40));
        } else {
            long e = t - boom;
            float decay = (float) Math.max(0.0, 1.0 - e / 3000.0);
            int fr = (int) Math.min(190, 25 + e * 0.38);
            circle(g, cx, gy, fr, argb((int) (220 * decay * fade), 255, 90, 15));
            circle(g, cx, gy, (int) (fr * 0.7), argb((int) (235 * decay * fade), 255, 170, 40));
            circle(g, cx, gy, (int) (fr * 0.4), argb((int) (255 * decay * fade), 255, 245, 190));
            for (int rg = 0; rg < 3; rg++) {
                double rad = Math.max(0, (e - rg * 300) * 0.9);
                ring(g, cx, gy, rad, 1.5, 0.35, (int) (200 * fade * Math.max(0, 1 - rad / (w * 0.9))), 255, 200, 120);
            }
            Random r = new Random(31);
            for (int i = 0; i < 12; i++) {
                int dir = i % 2 == 0 ? 1 : -1;
                double len = Math.min(w * 0.45, e * 0.25) * (0.5 + r.nextDouble());
                int dy = (int) ((r.nextDouble() - 0.5) * 60);
                line(g, cx, gy, (int) (cx + dir * len), gy + dy, 1, argb((int) (220 * Math.max(0, 1 - e / 4000.0) * fade), 255, 140, 30));
            }
            particles(g, cx, gy, e, 21, 100, 0.1, 0.6, 0.00022, 3500, ROCK, fade, 5);
            if (e > 700) nameCard(g, a, cx, h / 2, w, (float) Math.min(1.0, (e - 700) / 600.0) * fade, t, "METEOR IMPACT!", 255, 120, 40);
        }
        unshake(g);
        if (t >= boom) flash(g, w, h, t - boom, 900);
    }

    // ---- $1B rare B: orbital strike
    static void orbital(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2, cy = h / 2;
        long boom = 2700;
        g.fill(0, 0, w, h, argb((int) (170 * Math.min(1.0, t / 500.0) * fade), 8, 0, 0));
        float amp = 0f;
        if (t >= boom && t < boom + 1600) amp = 12f * (1f - (t - boom) / 1600f);
        shake(g, amp);
        if (t < boom) {
            double p = t / 2700.0;
            int R = (int) (130 - 90 * p);
            int al = (int) (255 * fade);
            ring(g, cx, cy, R, 1.0, 1.0, al, 255, 50, 50);
            ring(g, cx, cy, R / 2.0, 1.0, 1.0, (int) (160 * fade), 255, 50, 50);
            g.fill(cx - R - 20, cy, cx - R + 20, cy + 2, argb(al, 255, 50, 50));
            g.fill(cx + R - 20, cy, cx + R + 20, cy + 2, argb(al, 255, 50, 50));
            g.fill(cx, cy - R - 20, cx + 2, cy - R + 20, argb(al, 255, 50, 50));
            g.fill(cx, cy + R - 20, cx + 2, cy + R + 20, argb(al, 255, 50, 50));
            int digit = Math.max(1, 3 - (int) (t / 900));
            bigText(g, String.valueOf(digit), cx, cy - 16, 4.0f, argb(al, 255, 255, 255));
            if ((t / 200) % 2 == 0) bigText(g, "TARGET LOCKED", cx, 20, 2.2f, argb(al, 255, 70, 70));
        } else {
            long e = t - boom;
            int bot = e < 250 ? (int) (cy * e / 250.0) : cy + 20;
            double bf = e < 1600 ? 1.0 : Math.max(0, 1 - (e - 1600) / 700.0);
            int bw = (int) (30 + 14 * Math.sin(e / 40.0));
            g.fill(cx - (int) (bw * 1.6), 0, cx + (int) (bw * 1.6), bot, argb((int) (90 * bf * fade), 255, 60, 30));
            g.fill(cx - bw, 0, cx + bw, bot, argb((int) (170 * bf * fade), 255, 160, 60));
            g.fill(cx - bw / 3, 0, cx + bw / 3, bot, argb((int) (255 * bf * fade), 255, 255, 255));
            if (e >= 250) {
                long ee = e - 250;
                float decay = (float) Math.max(0.0, 1.0 - ee / 3000.0);
                int fr = (int) Math.min(160, 20 + ee * 0.33);
                circle(g, cx, cy, fr, argb((int) (210 * decay * fade), 255, 90, 15));
                circle(g, cx, cy, (int) (fr * 0.65), argb((int) (230 * decay * fade), 255, 190, 70));
                circle(g, cx, cy, (int) (fr * 0.35), argb((int) (255 * decay * fade), 255, 250, 210));
                for (int rg = 0; rg < 3; rg++) {
                    double rad = Math.max(0, (ee - rg * 300) * 0.85);
                    ring(g, cx, cy, rad, 1.4, 0.8, (int) (200 * fade * Math.max(0, 1 - rad / (w * 0.9))), 255, 180, 120);
                }
                particles(g, cx, cy, ee, 41, 90, 0.08, 0.55, 0.00018, 3200, FIRE, fade, 4);
            }
            if (e > 900) nameCard(g, a, cx, cy, w, (float) Math.min(1.0, (e - 900) / 600.0) * fade, t, "ORBITAL STRIKE!", 255, 80, 80);
        }
        unshake(g);
        if (t >= boom + 250) flash(g, w, h, t - boom - 250, 900);
    }

    static void nuke(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2;
        int al = (int) (255 * fade);

        // Phase 1: warning countdown
        if (t < 1700) {
            double pulse = 0.5 + 0.5 * Math.sin(t / 60.0);
            g.fill(0, 0, w, h, argb((int) (90 * pulse), 255, 0, 0));
            bigText(g, "!!! NUKE INCOMING !!!", cx, h / 2f - 20, 3.0f, argb(255, 255, 60, 60));
            bigText(g, a.name + " is dropping " + money(a.amount), cx, h / 2f + 20, 1.5f, argb(255, 255, 255, 255));
            return;
        }

        long e = t - 1700;
        // screen shake
        float sx = 0, sy = 0;
        if (e < 1200) {
            float amp = 14f * (1f - e / 1200f);
            sx = (float) ((Math.random() - 0.5) * amp);
            sy = (float) ((Math.random() - 0.5) * amp);
        }
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);

        // backdrop
        g.fill(-20, -20, w + 20, h + 20, argb((int) (150 * fade), 40, 10, 0));

        int baseY = (int) (h * 0.80);
        double p = Math.min(1.0, e / 2600.0);
        int stemH = (int) (130 * p), capR = (int) (75 * p) + 5;
        int capY = baseY - stemH - (int) (capR * 0.5);

        // ground fire
        circle(g, cx, baseY, (int) (60 * p) + 8, argb((int) (230 * fade), 255, 120, 20));
        // stem
        for (int i = 0; i < stemH; i++) {
            int half = (int) (12 + 10 * (1 - i / Math.max(1.0, (double) stemH)));
            g.fill(cx - half, baseY - i - 1, cx + half, baseY - i, argb((int) (230 * fade), 210, 90 + i / 2, 20));
        }
        // cap
        circle(g, cx, capY, capR, argb((int) (235 * fade), 230, 90, 15));
        circle(g, cx - capR / 4, capY - capR / 5, (int) (capR * 0.7), argb((int) (235 * fade), 255, 160, 40));
        circle(g, cx - capR / 3, capY - capR / 3, (int) (capR * 0.35), argb((int) (240 * fade), 255, 235, 150));

        // shockwave rings
        for (int ring = 0; ring < 2; ring++) {
            double rad = Math.max(0, (e - ring * 250) * 0.9);
            if (rad <= 0 || rad > w) continue;
            int ra = (int) (200 * fade * Math.max(0, 1 - rad / (w * 0.9)));
            for (int i = 0; i < 360; i += 3) {
                double ang = Math.toRadians(i);
                int x = (int) (cx + Math.cos(ang) * rad * 1.5);
                int y = (int) (baseY + Math.sin(ang) * rad * 0.35);
                g.fill(x, y, x + 3, y + 2, argb(ra, 255, 220, 150));
            }
        }

        // text
        if (e > 600) {
            float ta = (float) Math.min(1.0, (e - 600) / 600.0) * fade;
            int tal = (int) (255 * ta);
            float pulse = 1f + 0.06f * (float) Math.sin(t / 100.0);
            bigText(g, "BOUNTY NUKED", cx, 28, 2.6f, argb(tal, 255, 70, 40));
            bigText(g, a.name, cx, 62, 3.2f * pulse, argb(tal, 255, 255, 255));
            bigText(g, "+" + money(a.amount), cx, 100, 4.0f * pulse, argb(tal, 255, 215, 60));
        }
        g.pose().popMatrix();

        // white flash on detonation
        if (e < 1000) {
            g.fill(0, 0, w, h, argb((int) (255 * (1 - e / 1000.0)), 255, 255, 255));
        }
    }
}

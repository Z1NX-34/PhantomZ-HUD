package com.phantomz.hud;

import static com.phantomz.hud.HudDraw.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Boss bars for every boss event the game knows about (including other mods' bosses that use the vanilla system): name
 * with red slashes and gold diamonds, slanted bar with HP numbers and a drain trail, and a thin posture bar below it
 * when Epic Fight Posture Integration reports posture for that boss.
 */
final class BossBars {
    private BossBars() {}

    private static final Logger LOG = LogManager.getLogger("PhantomZHud/Boss");
    private static final Pattern PHASE = Pattern.compile("(?i)phase\\s*([0-9ivx]+)");
    private static final Map<UUID, Float> TRAIL = new HashMap<>();
    private static final Map<UUID, Float> POSTURE = new HashMap<>();
    private static long lastNanos;

    private static Field eventsField;
    private static boolean eventsFailed;

    /** True when boss events cannot be read, in which case the vanilla bars must stay visible. */
    static boolean unavailable() {
        return eventsFailed;
    }

    @SuppressWarnings("unchecked")
    private static List<LerpingBossEvent> events(Minecraft mc) {
        List<LerpingBossEvent> out = new ArrayList<>();
        if (eventsFailed) return out;
        try {
            BossHealthOverlay overlay = mc.gui.getBossOverlay();
            if (eventsField == null) {
                for (Field f : BossHealthOverlay.class.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        eventsField = f;
                        break;
                    }
                }
                if (eventsField == null) throw new IllegalStateException("no map field in BossHealthOverlay");
            }
            Map<UUID, LerpingBossEvent> map = (Map<UUID, LerpingBossEvent>) eventsField.get(overlay);
            out.addAll(map.values());
        } catch (Throwable t) {
            eventsFailed = true;
            LOG.warn("Boss events unreadable, vanilla bars stay hidden: {}", t.toString());
        }
        return out;
    }

    static List<String> debugLines() {
        Minecraft mc = Minecraft.getInstance();
        return PostureLink.dump(mc, events(mc));
    }

    static int count() {
        return eventsFailed ? 0 : events(Minecraft.getInstance()).size();
    }

    static void renderAll(GuiGraphics g, int sw, int sh, float scale) {
        Minecraft mc = Minecraft.getInstance();
        List<LerpingBossEvent> list = events(mc);
        if (list.isEmpty()) {
            if (!TRAIL.isEmpty()) {
                TRAIL.clear();
                POSTURE.clear();
            }
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        int max = HudConfig.BOSS_MAX.get();
        for (int i = 0; i < list.size() && i < max; i++) draw(g, mc, list.get(i), i, sw, sh, scale, dt);
    }

    private static String num(float v) {
        if (v > 0f && v < 1f) return "1";
        return String.format(Locale.ROOT, "%,d", Math.round(v));
    }

    /** Thin, minimal boss bar: name left, HP right, a slim slanted bar, and an even slimmer posture bar underneath. */
    private static void draw(GuiGraphics g, Minecraft mc, LerpingBossEvent ev, int index, int sw, int sh, float scale, float dt) {
        Font font = mc.font;
        PostureLink.Info info = PostureLink.lookup(mc, ev);
        LivingEntity ent = info.entity;

        // The bar's own progress is the truth, unless a boss whose name matches an entity says otherwise (some mods
        // keep the event progress at a fixed value and track the real health on the entity).
        float pct = PlayerBlock.clamp01(ev.getProgress());
        String hp = Math.round(pct * 100f) + "%";
        if (ent != null && ent.getMaxHealth() > 0f) {
            float ef = PlayerBlock.clamp01(ent.getHealth() / ent.getMaxHealth());
            if (info.byName && Math.abs(ef - pct) > 0.04f) pct = ef;
            hp = num(ent.getHealth()) + " / " + num(ent.getMaxHealth());
        }
        float trail = TRAIL.getOrDefault(ev.getId(), pct);
        if (pct >= trail) trail = pct; else trail = Math.max(pct, trail - dt * 0.30f);
        TRAIL.put(ev.getId(), trail);

        String name = ev.getName().getString();
        Matcher mt = PHASE.matcher(name);
        String phase = mt.find() ? "PHASE " + mt.group(1).toUpperCase(Locale.ROOT) : null;

        float w = Math.min(270f, sw / scale * 0.42f), h = 6.2f, sl = 4f;
        float nameScale = 0.95f, hpScale = 0.8f;
        float room = w - sl - font.width(hp) * hpScale - 16f - (phase == null ? 0f : 40f);
        String shown = name;
        while (shown.length() > 3 && font.width(shown) * nameScale > room) shown = shown.substring(0, shown.length() - 1);
        if (!shown.equals(name)) shown = shown.stripTrailing() + "...";

        float cx = sw / 2f, y = sh - 92f * scale - index * 34f * scale;
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();
        float x0 = -w / 2f, x1 = w / 2f, len = w - sl;

        // One palette for every boss (the event colour is ignored so all bars read the same): health in crimson, posture in
        // amber (analogous to the gold frame, clearly different from the red), gold structure, bone text.
        boolean low = pct < 0.25f && pct > 0f;
        float pulse = low ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 170.0) : 0f;
        int top = argb(230 + Math.round(20 * pulse), 62 + Math.round(30 * pulse), 74 + Math.round(20 * pulse), 255);
        int bot = argb(134, 24, 42, 255);

        // name (left) and phase tag, HP (right)
        g.pose().pushPose();
        g.pose().translate(x0 + sl, -11.4f, 0f);
        g.pose().scale(nameScale, nameScale, 1f);
        g.drawString(font, shown, 0, 0, BONE, true);
        if (phase != null) {
            g.pose().translate(font.width(shown) + 6f, 1.2f, 0f);
            g.pose().scale(0.72f, 0.72f, 1f);
            g.drawString(font, phase, 0, 0, GOLD, true);
        }
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(x1 - sl, -10.6f, 0f);
        g.pose().scale(hpScale, hpScale, 1f);
        g.drawString(font, hp, -font.width(hp), 0, withAlpha(CREAM, 235), true);
        g.pose().popPose();

        // main bar: gold frame (red-tinted when low), dark violet body, damage trail, crimson fill, segment notches
        int frame = low ? mix(GOLD, RED, 0.45f * pulse) : GOLD;
        poly(m, withAlpha(frame, 235), skewBand(x0 - 0.9f, sl, h, -0.9f, h + 0.9f, 0f, len + 1.8f));
        poly(m, withAlpha(argb(18, 14, 30, 255), 244), skewBand(x0, sl, h, 0f, h, 0f, len));
        float ins = 0.9f;
        if (trail > pct) poly(m, withAlpha(argb(236, 196, 186, 255), 205), skewBand(x0, sl, h, ins, h - ins, 0f, len * trail));
        if (pct > 0.002f) {
            float u = len * pct;
            polyColors(m, 0f, skewBand(x0, sl, h, ins, h - ins, 0f, u), new int[] {top, top, bot, bot});
            poly(m, withAlpha(WHITE, 70), skewBand(x0, sl, h, ins, ins + 1.1f, 0f, u));
        }
        for (int q = 1; q <= 3; q++) {
            float u = len * q / 4f;
            float[] t = skewBand(x0, sl, h, 0f, h, u, u);
            line(m, withAlpha(argb(18, 14, 30, 255), 170), t[0], 0f, t[6], h, 0.7f);
        }
        line(m, withAlpha(GOLD, 255), x0 + sl, 0f, x1, 0f, 0.8f);

        // posture bar
        if (info.posture >= 0f) {
            float pv = POSTURE.getOrDefault(ev.getId(), info.posture);
            pv += (info.posture - pv) * Math.min(1f, dt * 10f);
            POSTURE.put(ev.getId(), pv);
            float ph = 3.2f, py = h + 3.6f, psl = 2.2f, pw = w * 0.58f, plen = pw - psl, px0 = -pw / 2f;
            boolean flash = info.broken && ((System.currentTimeMillis() / 140) % 2 == 0);
            boolean thin = !info.broken && pv < 0.25f;
            int ptop = info.broken ? (flash ? WHITE : RED) : thin ? argb(255, 112, 52, 255) : argb(244, 198, 92, 255);
            int pbot = info.broken ? (flash ? WHITE : DRED) : thin ? argb(176, 52, 28, 255) : argb(178, 122, 40, 255);
            g.pose().pushPose();
            g.pose().translate(0f, py, 0f);
            Matrix4f pm = g.pose().last().pose();
            poly(pm, withAlpha(info.broken ? RED : GOLD, 190), skewBand(px0 - 0.7f, psl, ph, -0.7f, ph + 0.7f, 0f, plen + 1.4f));
            poly(pm, withAlpha(argb(18, 14, 30, 255), 244), skewBand(px0, psl, ph, 0f, ph, 0f, plen));
            if (pv > 0.01f) polyColors(pm, 0f, skewBand(px0, psl, ph, 0.7f, ph - 0.7f, 0f, plen * pv), new int[] {ptop, ptop, pbot, pbot});
            g.pose().popPose();
            g.pose().pushPose();
            g.pose().translate(px0 - 2.5f, py - 0.2f, 0f);
            g.pose().scale(0.55f, 0.55f, 1f);
            String lab = info.broken ? "BROKEN" : "POSTURE";
            g.drawString(font, lab, -font.width(lab), 0, info.broken ? RED : withAlpha(BONE, 190), true);
            g.pose().popPose();
        }
        g.pose().popPose();
    }

    private static int mix(int a, int b, float t) {
        t = PlayerBlock.clamp01(t);
        int r = Math.round(((a >> 16) & 255) * (1f - t) + ((b >> 16) & 255) * t);
        int gg = Math.round(((a >> 8) & 255) * (1f - t) + ((b >> 8) & 255) * t);
        int bb = Math.round((a & 255) * (1f - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | gg << 8 | bb;
    }
}

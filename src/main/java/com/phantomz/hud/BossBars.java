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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Boss bars for every boss event the game knows about (including other mods' bosses that use the vanilla system): name
 * and HP numbers, a flat gold-framed bar coloured per boss (event colour, or a custom colour from the config) with a drain
 * trail and bracket ornaments on both ends, and a thin posture bar below it when Epic Fight Posture Integration reports
 * posture for that boss.
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

    // ------------------------------------------------------------------------------------------ colours

    /** Base colour per boss event colour (what the boss event asks for, tuned to sit well with the gold frame). */
    private static int eventRgb(BossEvent.BossBarColor c) {
        return switch (c) {
            case PINK -> 0xEC5496;
            case BLUE -> 0x4A84F0;
            case RED -> 0xD63442;
            case GREEN -> 0x54B85C;
            case YELLOW -> 0xECBE3C;
            case PURPLE -> 0x9C5CE8;
            case WHITE -> 0xE2E2EC;
        };
    }

    private static List<? extends String> parsedFor;
    private static final List<Object[]> PARSED = new ArrayList<>();

    /** Custom colour from the config for this boss, or -1. */
    private static int customRgb(String name, LivingEntity ent) {
        List<? extends String> raw = HudConfig.BOSS_COLORS.get();
        if (raw != parsedFor) {
            parsedFor = raw;
            PARSED.clear();
            for (String line : raw) {
                int eq = line.lastIndexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String hex = line.substring(eq + 1).trim();
                if (hex.startsWith("#")) hex = hex.substring(1);
                try {
                    if (!key.isEmpty()) PARSED.add(new Object[] {key, Integer.parseInt(hex, 16) & 0xFFFFFF});
                } catch (NumberFormatException ignored) {
                    LOG.warn("boss_colors: '{}' is not a valid colour (use name=#RRGGBB)", line);
                }
            }
        }
        if (PARSED.isEmpty()) return -1;
        String lname = name.toLowerCase(Locale.ROOT);
        String id = ent == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString();
        for (Object[] e : PARSED) {
            String key = (String) e[0];
            if (lname.contains(key) || (id != null && (id.equals(key) || id.endsWith(":" + key) || id.contains(key)))) return (Integer) e[1];
        }
        return -1;
    }

    private static int lighten(int rgb, float t) {
        return mix(0xFF000000 | rgb, WHITE, t);
    }

    private static int darken(int rgb, float k) {
        int r = Math.round(((rgb >> 16) & 255) * k), g = Math.round(((rgb >> 8) & 255) * k), b = Math.round((rgb & 255) * k);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Hue in degrees (0-360) of an rgb colour. */
    private static float hue(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d < 0.001f) return 0f;
        float h = mx == r ? ((g - b) / d) % 6f : mx == g ? (b - r) / d + 2f : (r - g) / d + 4f;
        h *= 60f;
        return h < 0 ? h + 360f : h;
    }

    // ------------------------------------------------------------------------------------------ drawing

    /** Flat boss bar: name left, HP right, a gold-framed bar with a bracket-and-spike ornament on each end, posture below. */
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

        float w = Math.min(270f, sw / scale * 0.42f), h = 6.4f;
        float nameScale = 0.95f, hpScale = 0.8f;
        float room = w - font.width(hp) * hpScale - 12f - (phase == null ? 0f : 40f);
        String shown = name;
        while (shown.length() > 3 && font.width(shown) * nameScale > room) shown = shown.substring(0, shown.length() - 1);
        if (!shown.equals(name)) shown = shown.stripTrailing() + "...";

        // colour: a custom entry from the config, else the boss event's own colour, else crimson
        int base = customRgb(name, ent);
        if (base < 0) base = HudConfig.BOSS_EVENT_COLORS.get() ? eventRgb(ev.getColor()) : 0xD63442;
        boolean low = pct < 0.25f && pct > 0f;
        float pulse = low ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 170.0) : 0f;
        int top = lighten(base, 0.14f + 0.22f * pulse);
        int bot = darken(base, 0.5f);
        int pale = lighten(base, 0.62f);

        float cx = sw / 2f, y = sh - 92f * scale - index * 34f * scale;
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();
        float x0 = -w / 2f, x1 = w / 2f;

        // name (left) and phase tag, HP (right)
        g.pose().pushPose();
        g.pose().translate(x0 + 0.5f, -11.4f, 0f);
        g.pose().scale(nameScale, nameScale, 1f);
        g.drawString(font, shown, 0, 0, BONE, true);
        if (phase != null) {
            g.pose().translate(font.width(shown) + 6f, 1.2f, 0f);
            g.pose().scale(0.72f, 0.72f, 1f);
            g.drawString(font, phase, 0, 0, GOLD, true);
        }
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(x1 - 0.5f, -10.6f, 0f);
        g.pose().scale(hpScale, hpScale, 1f);
        g.drawString(font, hp, -font.width(hp), 0, withAlpha(CREAM, 235), true);
        g.pose().popPose();

        // flat bar: gold frame (tinted red when low), dark violet body, damage trail, coloured fill, quarter notches
        int frame = low ? mix(GOLD, RED, 0.45f * pulse) : GOLD;
        rect(m, withAlpha(frame, 235), x0 - 0.8f, -0.8f, x1 + 0.8f, h + 0.8f);
        rect(m, withAlpha(argb(18, 14, 30, 255), 244), x0, 0f, x1, h);
        float ins = 0.9f, inner = w - 2f * ins;
        if (trail > pct) rect(m, withAlpha(pale, 205), x0 + ins, ins, x0 + ins + inner * trail, h - ins);
        if (pct > 0.002f) {
            float xf = x0 + ins + inner * pct;
            polyColors(m, 0f, new float[] {x0 + ins, ins, xf, ins, xf, h - ins, x0 + ins, h - ins}, new int[] {top, top, bot, bot});
            rect(m, withAlpha(WHITE, 70), x0 + ins, ins, xf, ins + 1.1f);
        }
        for (int q = 1; q <= 3; q++) {
            float xq = x0 + w * q / 4f;
            line(m, withAlpha(argb(18, 14, 30, 255), 170), xq, 0f, xq, h, 0.7f);
        }

        // ends: a gold bracket with lips over the bar, a coloured inlay and a spike pointing outwards (dr = direction inwards)
        for (int dr = 1; dr >= -1; dr -= 2) {
            float ex = dr == 1 ? x0 : x1, mid = h / 2f;
            poly(m, HI_GOLD, ex - dr * 1.1f, -3.4f, ex - dr * 3.4f, -1.6f, ex - dr * 3.4f, h + 1.6f, ex - dr * 1.1f, h + 3.4f);
            poly(m, GOLD, ex - dr * 3.4f, -1.6f, ex - dr * 1.1f, -3.4f, ex - dr * 1.1f, -2.2f);
            poly(m, GOLD, ex - dr * 1.1f, -3.4f, ex + dr * 6f, -3.4f, ex + dr * 5f, -2.2f, ex - dr * 1.1f, -2.2f);
            poly(m, GOLD, ex - dr * 3.4f, h + 1.6f, ex - dr * 1.1f, h + 3.4f, ex - dr * 1.1f, h + 2.2f);
            poly(m, GOLD, ex - dr * 1.1f, h + 3.4f, ex + dr * 6f, h + 3.4f, ex + dr * 5f, h + 2.2f, ex - dr * 1.1f, h + 2.2f);
            poly(m, HI_GOLD, ex - dr * 3.4f, mid - 1.9f, ex - dr * 9.5f, mid, ex - dr * 3.4f, mid + 1.9f);
            rect(m, top, Math.min(ex - dr * 2.9f, ex - dr * 1.9f), -1f, Math.max(ex - dr * 2.9f, ex - dr * 1.9f), h + 1f);
        }

        // posture bar: flat and slim, in amber (or cool teal when the bar itself is amber / yellow / orange)
        if (info.posture >= 0f) {
            float pv = POSTURE.getOrDefault(ev.getId(), info.posture);
            pv += (info.posture - pv) * Math.min(1f, dt * 10f);
            POSTURE.put(ev.getId(), pv);
            float ph = 3.2f, py = h + 3.8f, pw = w * 0.58f, px0 = -pw / 2f, px1 = pw / 2f;
            float hu = hue(base);
            boolean cool = hu >= 25f && hu <= 70f && base != 0xE2E2EC;
            boolean flash = info.broken && ((System.currentTimeMillis() / 140) % 2 == 0);
            boolean thin = !info.broken && pv < 0.25f;
            int ptop = info.broken ? (flash ? WHITE : RED) : thin ? argb(255, 112, 52, 255) : cool ? argb(112, 210, 222, 255) : argb(244, 198, 92, 255);
            int pbot = info.broken ? (flash ? WHITE : DRED) : thin ? argb(176, 52, 28, 255) : cool ? argb(40, 118, 134, 255) : argb(178, 122, 40, 255);
            g.pose().pushPose();
            g.pose().translate(0f, py, 0f);
            Matrix4f pm = g.pose().last().pose();
            rect(pm, withAlpha(info.broken ? RED : GOLD, 190), px0 - 0.7f, -0.7f, px1 + 0.7f, ph + 0.7f);
            rect(pm, withAlpha(argb(18, 14, 30, 255), 244), px0, 0f, px1, ph);
            if (pv > 0.01f) {
                float pxf = px0 + 0.7f + (pw - 1.4f) * pv;
                polyColors(pm, 0f, new float[] {px0 + 0.7f, 0.7f, pxf, 0.7f, pxf, ph - 0.7f, px0 + 0.7f, ph - 0.7f}, new int[] {ptop, ptop, pbot, pbot});
            }
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

    private static final int HI_GOLD = argb(240, 204, 120, 255);

    private static int mix(int a, int b, float t) {
        t = PlayerBlock.clamp01(t);
        int r = Math.round(((a >> 16) & 255) * (1f - t) + ((b >> 16) & 255) * t);
        int gg = Math.round(((a >> 8) & 255) * (1f - t) + ((b >> 8) & 255) * t);
        int bb = Math.round((a & 255) * (1f - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | gg << 8 | bb;
    }
}

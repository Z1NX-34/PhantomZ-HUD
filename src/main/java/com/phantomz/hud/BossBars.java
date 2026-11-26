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
 * and HP numbers above a pixel-art bar (thick frame, six segments, a skull plate on top, thorn tufts on the corners)
 * coloured per boss (the boss event's colour, or a custom colour from the config) with a drain trail, and a slim posture
 * bar below it when Epic Fight Posture Integration reports posture for that boss.
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

    /** One art pixel in GUI units: the whole bar is drawn on this grid, like pixel art. */
    private static final float PX = 1.1f;
    private static final int BAR_ROWS = 13;

    /** Centre piece above the bar: crown, skull plate, thorn branches. 27 columns, rows -10..0, centred on the bar. */
    private static final String[] EMBLEM = {
        "..........KKKKKKK..........",
        "........KGGKAaAKGGK........",
        "....AA..KGGKKKKKGGK..AA....",
        "....aA..KPPPPPPPPPK..Aa....",
        "..AAA...KAPPWWWPPAK...AAA..",
        ".KK.K...KAPWWWWWPAK...K.KK.",
        "..KKKKKKKAPWPWPWPAKKKKKK...",
        ".KK.K.K.KAPWWWWWPAK.K.K.KK.",
        ".AAA....KAPPWPWPPAK....AAA.",
        "...ggPP.KAPPPPPPPAK.PPgg...",
        "..gAAAA.KAAaaaaaAAK.AAAAg..",
    };
    private static final int EMBLEM_TOP = -10, EMBLEM_COLS = 27;
    /** Thorn tuft on each top corner (rows -7..-1, col 0 = the bar's outline column, mirrored on the right). */
    private static final String[] TUFT = {
        "..A...A.....",
        ".AAa.AAa....",
        "..K.K.K..AA.",
        ".KKKKKKK.Aa.",
        "..KKKKKKKK..",
        "KKKKKKKKKK..",
        "KKKKKKKKKKK.",
    };
    private static final int TUFT_TOP = -7;
    /** Small jagged fringe under each bottom corner (rows 13..14). */
    private static final String[] FRINGE = {
        "K.K.KK....",
        "..K.......",
    };

    private static int[] frameColors(int base) {
        float hu = hue(base);
        boolean grey = hu >= 260f && hu < 340f || (base >> 16 & 255) > 215 && (base >> 8 & 255) > 215;
        if (grey) return new int[] {0xFF313133, 0xFF404145, 0xFF2E2E30, 0xFF19181D, 0xFF141416};      // fA fB low1 low2 side
        if (hu >= 70f && hu < 260f) return new int[] {0xFF59392A, 0xFF795940, 0xFF5E3E2F, 0xFF3F291E, 0xFF1E1410};
        return new int[] {0xFF3C0616, 0xFF620A1E, 0xFF500019, 0xFF340410, 0xFF1E060C};
    }

    private static void rectPx(Matrix4f m, int color, float x0, float y0, int col, int row, int cols, int rows) {
        rect(m, color, x0 + col * PX, y0 + row * PX, x0 + (col + cols) * PX, y0 + (row + rows) * PX);
    }

    private static int paletteColor(char c, int[] fr, int base, int plate) {
        return switch (c) {
            case 'K' -> 0xFF050308;
            case 'G' -> fr[1];
            case 'g' -> fr[0];
            case 'P' -> plate;
            case 'A' -> 0xFF000000 | base;
            case 'a' -> lighten(base, 0.38f);
            case 'W' -> 0xFFF2F2F6;
            default -> 0;
        };
    }

    /** Draws an ASCII pixel map (merging equal neighbours into one rectangle). mirror flips it around the bar's centre. */
    private static void drawMap(Matrix4f m, String[] rows, int top, int col0, boolean mirror, int n, float x0, int[] fr, int base, int plate) {
        for (int r = 0; r < rows.length; r++) {
            String line = rows[r];
            int c = 0;
            while (c < line.length()) {
                char ch = line.charAt(c);
                int e = c;
                while (e + 1 < line.length() && line.charAt(e + 1) == ch) e++;
                if (ch != '.') {
                    int col = mirror ? n - 1 - (col0 + e) : col0 + c;
                    rectPx(m, paletteColor(ch, fr, base, plate), x0, 0f, col, top + r, e - c + 1, 1);
                }
                c = e + 1;
            }
        }
    }

    /** Pixel-art boss bar: thick framed bar in six segments, a skull plate on top and thorn tufts on the corners. */
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

        float w = Math.min(270f, sw / scale * 0.42f);
        int n = Math.max(61, Math.round(w / PX) | 1);                  // columns, odd so the emblem has a centre column
        float x0 = -n * PX / 2f, x1 = x0 + n * PX;
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
        int[] fr = frameColors(base);
        int plate = darken(base, 0.28f), band = darken(base, 0.28f), band2 = darken(base, 0.22f), empty = darken(base, 0.2f);
        int fillTop = lighten(base, 0.05f + 0.25f * pulse), fillMid = darken(base, 0.66f), fillBot = lighten(base, 0.25f + 0.2f * pulse);
        int pale = lighten(base, 0.62f);

        float cx = sw / 2f, y = sh - 92f * scale - index * 50f * scale;
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();

        // name (left) and HP (right) above the emblem
        float ty = EMBLEM_TOP * PX - 9.4f;
        g.pose().pushPose();
        g.pose().translate(x0 + 0.5f, ty, 0f);
        g.pose().scale(nameScale, nameScale, 1f);
        g.drawString(font, shown, 0, 0, BONE, true);
        if (phase != null) {
            g.pose().translate(font.width(shown) + 6f, 1.2f, 0f);
            g.pose().scale(0.72f, 0.72f, 1f);
            g.drawString(font, phase, 0, 0, GOLD, true);
        }
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(x1 - 0.5f, ty + 0.8f, 0f);
        g.pose().scale(hpScale, hpScale, 1f);
        g.drawString(font, hp, -font.width(hp), 0, withAlpha(CREAM, 235), true);
        g.pose().popPose();

        // frame: black body, then the horizontal bands (rows 1-2 top frame, 3 / 8 dark bands, 9 / 11 lower frame)
        rectPx(m, 0xFF050308, x0, 0f, 0, 0, n, BAR_ROWS);
        rectPx(m, fr[4], x0, 0f, 1, 1, 1, 11);
        rectPx(m, fr[4], x0, 0f, n - 2, 1, 1, 11);
        rectPx(m, fr[0], x0, 0f, 2, 1, n - 4, 1);
        rectPx(m, fr[1], x0, 0f, 2, 2, n - 4, 1);
        rectPx(m, band, x0, 0f, 2, 3, n - 4, 1);
        rectPx(m, empty, x0, 0f, 2, 4, n - 4, 4);
        rectPx(m, band2, x0, 0f, 2, 8, n - 4, 1);
        rectPx(m, fr[2], x0, 0f, 2, 9, n - 4, 1);
        rectPx(m, fr[3], x0, 0f, 2, 11, n - 4, 1);

        // fill: damage trail, then three tones (top light, two middle rows, bottom light), end caps and the segment dividers
        float fx0 = x0 + 3 * PX, fw = (n - 6) * PX;
        if (trail > pct) rect(m, withAlpha(pale, 205), fx0, 4 * PX, fx0 + fw * trail, 8 * PX);
        if (pct > 0.002f) {
            float xf = fx0 + fw * pct;
            rect(m, fillTop, fx0, 4 * PX, xf, 5 * PX);
            rect(m, fillMid, fx0, 5 * PX, xf, 7 * PX);
            rect(m, fillBot, fx0, 7 * PX, xf, 8 * PX);
            rect(m, lighten(base, 0.6f), fx0, 4 * PX, fx0 + PX, 8 * PX);
            if (pct > 0.985f) rect(m, lighten(base, 0.6f), fx0 + fw - PX, 4 * PX, fx0 + fw, 8 * PX);
        }
        for (float f : new float[] {0.107f, 0.295f, 0.5f, 0.705f, 0.893f}) {
            float dx = fx0 + fw * f - PX / 2f;
            rect(m, 0xFF050308, dx, 4 * PX, dx + PX, 9 * PX);
            rect(m, 0xFF050308, dx - PX, 9 * PX, dx + 2f * PX, 10 * PX);
        }

        // ornaments: tufts on both top corners, fringe under both bottom corners, the skull plate in the middle
        drawMap(m, TUFT, TUFT_TOP, 0, false, n, x0, fr, base, plate);
        drawMap(m, TUFT, TUFT_TOP, 0, true, n, x0, fr, base, plate);
        drawMap(m, FRINGE, BAR_ROWS, 0, false, n, x0, fr, base, plate);
        drawMap(m, FRINGE, BAR_ROWS, 0, true, n, x0, fr, base, plate);
        drawMap(m, EMBLEM, EMBLEM_TOP, (n - EMBLEM_COLS) / 2, false, n, x0, fr, base, plate);

        // posture bar: the same pixel style, slimmer, centred under the bar (amber, or cool teal when the bar itself is amber)
        if (info.posture >= 0f) {
            float pv = POSTURE.getOrDefault(ev.getId(), info.posture);
            pv += (info.posture - pv) * Math.min(1f, dt * 10f);
            POSTURE.put(ev.getId(), pv);
            int pn = Math.max(31, Math.round(n * 0.58f) | 1);
            float px0 = -pn * PX / 2f, py = (BAR_ROWS + 1) * PX;
            float hu = hue(base);
            boolean cool = hu >= 25f && hu <= 70f;
            boolean flash = info.broken && ((System.currentTimeMillis() / 140) % 2 == 0);
            boolean thin = !info.broken && pv < 0.25f;
            int pb = info.broken ? (flash ? 0xFFFFFFFF : 0xFFC42026) : thin ? 0xFFFF7034 : cool ? 0xFF70D2DE : 0xFFF4C65C;
            g.pose().pushPose();
            g.pose().translate(0f, py, 0f);
            Matrix4f pm = g.pose().last().pose();
            rect(pm, 0xFF050308, px0, 0f, px0 + pn * PX, 6 * PX);
            rect(pm, fr[0], px0 + PX, PX, px0 + (pn - 1) * PX, 5 * PX);
            rect(pm, darken(pb, 0.2f), px0 + 2 * PX, 2 * PX, px0 + (pn - 2) * PX, 4 * PX);
            if (pv > 0.01f) {
                float pxf = px0 + 2 * PX + (pn - 4) * PX * pv;
                rect(pm, lighten(pb, 0.1f), px0 + 2 * PX, 2 * PX, pxf, 3 * PX);
                rect(pm, darken(pb, 0.62f), px0 + 2 * PX, 3 * PX, pxf, 4 * PX);
            }
            g.pose().popPose();
            g.pose().pushPose();
            g.pose().translate(px0 - 2.5f, py + 0.4f, 0f);
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

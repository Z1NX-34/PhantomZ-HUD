package com.phantomz.hud;

import static com.phantomz.hud.HudDraw.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Compass after Skyrim's: a thin dark bar with the direction letters standing on it and an engraved gold lozenge on each
 * end (Skyrim's is silver). The letters are drawn as thin angular strokes with a dark outline so they stay crisp at any
 * GUI scale. Nothing else: no ticks, no pointer, no degree readout. Remnants Minimap markers sit on the bar with their
 * distance below.
 */
final class CompassBar {
    private CompassBar() {}

    private static final Logger LOG = LogManager.getLogger("PhantomZHud/Compass");
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float VIEW = 110f, BAND = 5f, MID = 9f;
    private static final float KNOT_HW = 14f, KNOT_HH = 7.6f;
    private static final int LETTER = argb(246, 242, 228, 255);

    static void render(GuiGraphics g, Font font, Player p, int sw, float scale, float partial) {
        float W = HudConfig.COMPASS_WIDTH.get();
        g.pose().pushPose();
        g.pose().translate(sw / 2f, 5f, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();

        float half = W / 2f, ppd = W / VIEW;
        float heading = (Mth.wrapDegrees(p.getViewYRot(partial) + 180f) + 360f) % 360f;

        // thin dark bar, a touch lighter along its top edge, slightly see-through
        rect(m, argb(22, 24, 52, 205), -half, MID - BAND / 2f, half, MID + BAND / 2f);
        rect(m, argb(66, 70, 118, 170), -half, MID - BAND / 2f, half, MID - BAND / 2f + 0.8f);

        // letters standing on the bar (they fade out before the lozenges)
        for (int deg = 0; deg < 360; deg += 45) {
            float d = Mth.wrapDegrees(deg - heading);
            if (Math.abs(d) > VIEW / 2f) continue;
            float x = d * ppd;
            float edgeFade = PlayerBlock.clamp01((half - Math.abs(x) - 3f) / 9f);
            if (edgeFade <= 0.02f) continue;
            word(m, NAMES[deg / 45], x, MID, deg % 90 == 0 ? 8.2f : 5.6f, edgeFade);
        }

        // markers from Remnants Minimap
        if (HudConfig.COMPASS_MARKERS.get()) Markers.draw(g, font, p, heading, ppd);

        m = g.pose().last().pose();
        knot(m, -half - KNOT_HW, 1);
        knot(m, half + KNOT_HW, -1);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------------------------------ letters

    /** Strokes of each letter in a unit box (x 0..1 left to right, y 0..1 top to bottom), as polylines. */
    private static float[][] strokes(char c) {
        return switch (c) {
            case 'N' -> new float[][] {{0.1f, 1f, 0.1f, 0f, 0.9f, 1f, 0.9f, 0f}};
            case 'E' -> new float[][] {{0.9f, 0f, 0.1f, 0f, 0.1f, 1f, 0.9f, 1f}, {0.1f, 0.5f, 0.74f, 0.5f}};
            case 'S' -> new float[][] {{0.9f, 0.14f, 0.76f, 0f, 0.24f, 0f, 0.1f, 0.14f, 0.1f, 0.36f, 0.24f, 0.5f, 0.76f, 0.5f,
                    0.9f, 0.64f, 0.9f, 0.86f, 0.76f, 1f, 0.24f, 1f, 0.1f, 0.86f}};
            default -> new float[][] {{0f, 0f, 0.25f, 1f, 0.5f, 0.3f, 0.75f, 1f, 1f, 0f}};     // W
        };
    }

    /** A one or two letter word centred on (cx, cy), h tall, white strokes with a dark outline. */
    private static void word(Matrix4f m, String s, float cx, float cy, float h, float fade) {
        float cw = 0.74f * h, gap = 0.22f * h, total = s.length() * cw + (s.length() - 1) * gap;
        float sw = Math.max(0.85f, h * 0.15f);
        int white = withAlpha(LETTER, Math.round(255 * fade)), black = withAlpha(INK, Math.round(255 * fade));
        for (int pass = 0; pass < 2; pass++) {
            float wd = pass == 0 ? sw + 1.3f : sw;
            int col = pass == 0 ? black : white;
            for (int i = 0; i < s.length(); i++) {
                float ox = cx - total / 2f + i * (cw + gap), oy = cy - h / 2f;
                for (float[] st : strokes(s.charAt(i))) {
                    for (int k = 0; k + 3 < st.length; k += 2) {
                        line(m, col, ox + st[k] * cw, oy + st[k + 1] * h, ox + st[k + 2] * cw, oy + st[k + 3] * h, wd);
                    }
                    for (int k = 0; k < st.length; k += 2) diamondFill(m, col, ox + st[k] * cw, oy + st[k + 1] * h, wd * 0.5f, wd * 0.5f);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ ends

    /**
     * The end ornament: an engraved gold lozenge (flat top and bottom, pointed sides) with a black outline, a fine inner
     * border and two linked diamond rings. in = +1 for the left end (bar to its right), -1 on the right.
     */
    private static void knot(Matrix4f m, float cx, int in) {
        float hw = KNOT_HW, hh = KNOT_HH, fl = 5f;
        float[] hex = {-hw, 0f, -fl, -hh, fl, -hh, hw, 0f, fl, hh, -fl, hh};
        float[] halo = new float[12], fill = new float[12], inner = new float[12];
        for (int i = 0; i < 6; i++) {
            halo[i * 2] = cx + hex[i * 2] * (1f + 1.1f / hw);
            halo[i * 2 + 1] = MID + hex[i * 2 + 1] * (1f + 1.1f / hh);
            fill[i * 2] = cx + hex[i * 2];
            fill[i * 2 + 1] = MID + hex[i * 2 + 1];
            inner[i * 2] = cx + hex[i * 2] * 0.8f;
            inner[i * 2 + 1] = MID + hex[i * 2 + 1] * 0.8f;
        }
        poly(m, INK, halo);
        int tip = argb(232, 190, 98, 255), hi = argb(255, 240, 184, 255), lo = argb(202, 154, 66, 255);
        polyColors(m, 0f, fill, new int[] {tip, hi, hi, tip, lo, lo});
        int cut = argb(124, 84, 28, 255);
        for (int i = 0; i < 6; i++) {
            int n = (i + 1) % 6;
            line(m, cut, inner[i * 2], inner[i * 2 + 1], inner[n * 2], inner[n * 2 + 1], 0.5f);
        }
        float r = 4.7f, off = 3.9f;
        diamondLine(m, cut, cx - off, MID, r, r, 0.55f);
        diamondLine(m, cut, cx + off, MID, r, r, 0.55f);
        diamondLine(m, cut, cx - 9.6f, MID, 1.3f, 1.3f, 0.45f);
        diamondLine(m, cut, cx + 9.6f, MID, 1.3f, 1.3f, 0.45f);
        diamondFill(m, cut, cx, MID, 1.3f, 1.3f);
        diamondFill(m, hi, cx, MID, 0.5f, 0.5f);
        line(m, withAlpha(WHITE, 140), cx - fl * 0.9f, MID - hh + 0.7f, cx + fl * 0.9f, MID - hh + 0.7f, 0.6f);
    }

    /** Remnants Minimap markers by reflection; switches itself off after repeated failures. */
    private static final class Markers {
        private static boolean failed;
        private static int errors;
        private static Method sessionM, dimM, inDim, tint;
        private static Field markersF, hiddenF, xF, yF, zF, hasYF, kindF, nameF;
        private static boolean init;

        static void draw(GuiGraphics g, Font font, Player p, float heading, float ppd) {
            if (failed || !ModList.get().isLoaded("remnantminimaps")) return;
            try {
                if (!init) {
                    Class<?> rc = Class.forName("io.nightbeam.remnantminimaps.client.RemnantClient");
                    sessionM = rc.getMethod("session");
                    dimM = rc.getMethod("currentDimension");
                    Class<?> ms = Class.forName("io.nightbeam.remnantminimaps.client.session.MapSession");
                    markersF = ms.getField("markers");
                    Class<?> store = Class.forName("io.nightbeam.remnantminimaps.core.marker.MarkerStore");
                    inDim = store.getMethod("inDimension", String.class);
                    Class<?> mk = Class.forName("io.nightbeam.remnantminimaps.core.marker.Marker");
                    hiddenF = mk.getField("hidden");
                    xF = mk.getField("x");
                    yF = mk.getField("y");
                    zF = mk.getField("z");
                    hasYF = mk.getField("hasY");
                    kindF = mk.getField("kind");
                    nameF = mk.getField("name");
                    tint = mk.getMethod("tint");
                    init = true;
                }
                Object session = sessionM.invoke(null);
                String dim = (String) dimM.invoke(null);
                if (session == null || dim == null) return;
                Object store = markersF.get(session);
                if (store == null) return;
                List<?> list = new ArrayList<>((List<?>) inDim.invoke(store, dim));
                double range = HudConfig.COMPASS_RANGE.get(), vlim = HudConfig.COMPASS_VERTICAL.get();
                boolean deaths = HudConfig.COMPASS_DEATHS.get();
                for (Object mk : list) {
                    if (hiddenF.getBoolean(mk)) continue;
                    if (!deaths && String.valueOf(kindF.get(mk)).equals("DEATH")) continue;
                    double dx = xF.getInt(mk) + 0.5 - p.getX(), dz = zF.getInt(mk) + 0.5 - p.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (range > 0 && dist > range) continue;
                    if (vlim > 0 && hasYF.getBoolean(mk) && Math.abs(yF.getInt(mk) - p.getY()) > vlim) continue;
                    float bearing = (float) Math.toDegrees(Math.atan2(dx, -dz));
                    float d = Mth.wrapDegrees(bearing - heading);
                    if (Math.abs(d) > VIEW / 2f - 3f) continue;
                    float x = d * ppd;
                    int rgb = ((Integer) tint.invoke(mk)) & 0xFFFFFF;
                    int col = 0xFF000000 | rgb;
                    Matrix4f m = g.pose().last().pose();
                    diamondFill(m, INK, x, MID, 4.2f, 4.2f);
                    diamondFill(m, col, x, MID, 3.1f, 3.1f);
                    diamondLine(m, withAlpha(GOLD, 230), x, MID, 4.2f, 4.2f, 0.6f);
                    String s = dist >= 1000 ? String.format("%.1fk", dist / 1000.0) : Math.round(dist) + "m";
                    g.pose().pushPose();
                    g.pose().translate(x, MID + 5.4f, 0);
                    g.pose().scale(0.5f, 0.5f, 1f);
                    g.drawString(font, s, Math.round(-font.width(s) / 2f), 0, BONE, true);
                    g.pose().popPose();
                }
                errors = 0;
            } catch (Throwable t) {
                if (++errors >= 5) {
                    failed = true;
                    LOG.warn("Remnants markers disabled on the compass: {}", t.toString());
                }
            }
        }
    }
}

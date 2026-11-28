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
 * Compass after Skyrim's: a thin translucent dark bar with the direction letters standing on it, flowing into a gold
 * line-art lozenge on each end (Skyrim's is silver): the bar's body and the ornament are one outline, with a woven pattern
 * of linked diamond rings inside. The letters are thin angular strokes with a dark outline so they stay crisp at any GUI
 * scale. Nothing else: no ticks, no pointer, no degree readout. Remnants Minimap markers sit on the bar with their
 * distance below.
 */
final class CompassBar {
    private CompassBar() {}

    private static final Logger LOG = LogManager.getLogger("PhantomZHud/Compass");
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float VIEW = 110f, BAND = 5f, MID = 9f;
    private static final float KNOT_HW = 14f, KNOT_HH = 7.6f, KNOT_FL = 5f;
    private static final int BODY = argb(22, 24, 52, 205), ORN = argb(226, 184, 92, 255), HALO = argb(14, 12, 14, 170);
    private static final int LETTER = argb(246, 242, 228, 255);

    static void render(GuiGraphics g, Font font, Player p, int sw, float scale, float partial) {
        float W = HudConfig.COMPASS_WIDTH.get();
        g.pose().pushPose();
        g.pose().translate(sw / 2f, 5f, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();

        float half = W / 2f, ppd = W / VIEW;
        float heading = (Mth.wrapDegrees(p.getViewYRot(partial) + 180f) + 360f) % 360f;

        // thin dark bar, a touch lighter along its top edge, slightly see-through, with a fine gold rail on both edges
        rect(m, BODY, -half, MID - BAND / 2f, half, MID + BAND / 2f);
        rect(m, argb(66, 70, 118, 150), -half, MID - BAND / 2f, half, MID - BAND / 2f + 0.7f);
        for (int k = -1; k <= 1; k += 2) line(m, HALO, -half, MID + k * BAND / 2f, half, MID + k * BAND / 2f, 1.5f);
        for (int k = -1; k <= 1; k += 2) line(m, withAlpha(ORN, 170), -half, MID + k * BAND / 2f, half, MID + k * BAND / 2f, 0.5f);

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
        float xn = neck();
        knot(m, -half - xn, 1);
        knot(m, half + xn, -1);
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

    /** Local x where the lozenge's slanted side meets the bar's edge (the bar continues from there). */
    private static float neck() {
        float t = (KNOT_HH - BAND / 2f) / KNOT_HH;
        return KNOT_FL + (KNOT_HW - KNOT_FL) * t;
    }

    /**
     * The end ornament: the bar widens into a gold-outlined lozenge (flat top and bottom, a point on the outer side). Inside:
     * a fine inner border and three overlapping diamond rings, woven, with gold studs at the crossings. in = +1 for the
     * left end (bar to its right), -1 on the right; local x points towards the bar.
     */
    private static void knot(Matrix4f m, float cx, int in) {
        float hw = KNOT_HW, hh = KNOT_HH, fl = KNOT_FL, bt = BAND / 2f, xn = neck();
        float[] v = {-hw, 0f, -fl, -hh, fl, -hh, xn, -bt, xn, bt, fl, hh, -fl, hh};
        float[] fill = new float[14];
        for (int i = 0; i < 7; i++) {
            fill[i * 2] = cx + in * v[i * 2];
            fill[i * 2 + 1] = MID + v[i * 2 + 1];
        }
        poly(m, BODY, fill);
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {4, 5}, {5, 6}, {6, 0}};
        for (int[] e : edges) line(m, HALO, fill[e[0] * 2], fill[e[0] * 2 + 1], fill[e[1] * 2], fill[e[1] * 2 + 1], 1.9f);
        for (int[] e : edges) line(m, ORN, fill[e[0] * 2], fill[e[0] * 2 + 1], fill[e[1] * 2], fill[e[1] * 2 + 1], 0.75f);
        for (int i : new int[] {0, 1, 2, 5, 6}) diamondFill(m, ORN, fill[i * 2], fill[i * 2 + 1], 0.4f, 0.4f);

        // fine inner border (open on the bar side)
        float k = 0.78f;
        float[] pl = {fl * k, hh * k, -fl * k, hh * k, -hw * k, 0f, -fl * k, -hh * k, fl * k, -hh * k};
        for (int i = 0; i + 3 < pl.length; i += 2) {
            line(m, withAlpha(ORN, 150), cx + in * pl[i], MID + pl[i + 1], cx + in * pl[i + 2], MID + pl[i + 3], 0.45f);
        }

        // three overlapping diamond rings, a nested openwork centre and small hollow studs where the rings cross (all line-art)
        float r = 4.8f;
        float[] cs = {-5.8f, -1f, 3.8f};
        for (float c : cs) diamondLine(m, withAlpha(ORN, 235), cx + in * c, MID, r, r, 0.55f);
        diamondLine(m, withAlpha(ORN, 235), cx + in * cs[1], MID, 2.7f, 2.7f, 0.5f);
        diamondLine(m, withAlpha(ORN, 200), cx + in * cs[1], MID, 1.5f, 1.5f, 0.45f);
        diamondFill(m, ORN, cx + in * cs[1], MID, 0.45f, 0.45f);
        for (int i = 0; i < 2; i++) {
            float xm = (cs[i] + cs[i + 1]) / 2f, dy = r - (cs[i + 1] - cs[i]) / 2f;
            diamondLine(m, ORN, cx + in * xm, MID - dy, 0.95f, 0.95f, 0.4f);
            diamondLine(m, ORN, cx + in * xm, MID + dy, 0.95f, 0.95f, 0.4f);
        }
        diamondLine(m, ORN, cx + in * cs[0], MID, 1.2f, 1.2f, 0.4f);
        diamondLine(m, ORN, cx + in * cs[2], MID, 1.2f, 1.2f, 0.4f);
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

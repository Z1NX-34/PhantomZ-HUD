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
 * Compass after Skyrim's: a thin dark bar with the direction letters standing on it (white, black outline), and a
 * pierced gold lozenge on each end (Skyrim's is silver). Nothing else: no ticks, no pointer, no degree readout.
 * Remnants Minimap markers sit on the bar with their distance below.
 */
final class CompassBar {
    private CompassBar() {}

    private static final Logger LOG = LogManager.getLogger("PhantomZHud/Compass");
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float VIEW = 110f, BAND = 5f, MID = 9f;
    private static final float KNOT_HW = 15f, KNOT_HH = 8.5f;

    static void render(GuiGraphics g, Font font, Player p, int sw, float scale, float partial) {
        float W = HudConfig.COMPASS_WIDTH.get();
        g.pose().pushPose();
        g.pose().translate(sw / 2f, 5f, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();

        float half = W / 2f, ppd = W / VIEW;
        float heading = (Mth.wrapDegrees(p.getViewYRot(partial) + 180f) + 360f) % 360f;

        // thin dark bar, a touch lighter along its top edge, slightly see-through
        rect(m, argb(24, 24, 50, 205), -half, MID - BAND / 2f, half, MID + BAND / 2f);
        rect(m, argb(62, 64, 108, 170), -half, MID - BAND / 2f, half, MID - BAND / 2f + 1f);

        // letters standing on the bar (they fade out before the lozenges)
        for (int deg = 0; deg < 360; deg += 45) {
            float d = Mth.wrapDegrees(deg - heading);
            if (Math.abs(d) > VIEW / 2f) continue;
            float x = d * ppd;
            float edgeFade = PlayerBlock.clamp01((half - Math.abs(x) - 3f) / 9f);
            if (edgeFade <= 0.02f) continue;
            String s = NAMES[deg / 45];
            float ts = deg % 90 == 0 ? 1.15f : 0.8f;
            outlined(g, font, s, x, MID - 3.5f * ts, ts, Math.round(255 * edgeFade));
        }

        // markers from Remnants Minimap
        if (HudConfig.COMPASS_MARKERS.get()) Markers.draw(g, font, p, heading, ppd);

        m = g.pose().last().pose();
        knot(m, -half - KNOT_HW, 1);
        knot(m, half + KNOT_HW, -1);
        g.pose().popPose();
    }

    /** White text with a thin black outline (drawn four times offset), centred on x. */
    private static void outlined(GuiGraphics g, Font font, String s, float x, float y, float ts, int alpha) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(ts, ts, 1f);
        int ox = Math.round(-font.width(s) / 2f);
        int black = withAlpha(INK, alpha), white = withAlpha(0xFFF4F4F8, alpha);
        float o = 0.75f;
        float[][] off = {{o, 0f}, {-o, 0f}, {0f, o}, {0f, -o}};
        for (float[] f : off) {
            g.pose().pushPose();
            g.pose().translate(f[0], f[1], 0f);
            g.drawString(font, s, ox, 0, black, false);
            g.pose().popPose();
        }
        g.drawString(font, s, ox, 0, white, false);
        g.pose().popPose();
    }

    /**
     * The end ornament: a gold lozenge (flat top and bottom, pointed sides) with a black outline and a lattice of small
     * pierced holes plus one larger ring on the side facing the bar. in = +1 for the left end (bar to its right), -1 on the right.
     */
    private static void knot(Matrix4f m, float cx, int in) {
        float hw = KNOT_HW, hh = KNOT_HH, fl = 5.5f;
        float[] hex = {-hw, 0f, -fl, -hh, fl, -hh, hw, 0f, fl, hh, -fl, hh};
        float[] out = new float[12], fill = new float[12];
        for (int i = 0; i < 6; i++) {
            out[i * 2] = cx + hex[i * 2] * 1.075f;
            out[i * 2 + 1] = MID + hex[i * 2 + 1] * 1.13f;
            fill[i * 2] = cx + hex[i * 2];
            fill[i * 2 + 1] = MID + hex[i * 2 + 1];
        }
        poly(m, INK, out);
        int tip = argb(226, 178, 84, 255), hi = argb(255, 232, 160, 255), lo = argb(176, 126, 46, 255);
        polyColors(m, 0f, fill, new int[] {tip, hi, hi, tip, lo, lo});
        int hole = argb(58, 40, 22, 255);
        float[][] holes = {{-9.5f, 0.4f}, {-5.8f, -3.5f}, {-5.8f, 3.4f}, {-2f, -0.4f}, {1.8f, -3.6f}, {1.8f, 3.5f}};
        for (float[] h : holes) diamondFill(m, hole, cx + in * h[0], MID + h[1], 1.15f, 1.15f);
        diamondLine(m, hole, cx + in * 8.6f, MID, 3.4f, 3.4f, 0.9f);
        diamondFill(m, hole, cx + in * 8.6f, MID, 1.2f, 1.2f);
        line(m, withAlpha(WHITE, 120), cx - hw * 0.55f, MID - hh + 0.6f, cx + hw * 0.55f, MID - hh + 0.6f, 0.6f);
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

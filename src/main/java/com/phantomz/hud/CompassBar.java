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
 * Minimal compass: a thin, translucent dark bar with a fine gold rail on its top and bottom edge, small letters and tick
 * marks sliding along it, and a woven diamond-knot ornament on each end (no flat end plate: the ornament itself is the
 * end). Nothing else: no pointer, no degree readout. Remnants Minimap markers sit on the bar with their distance below.
 */
final class CompassBar {
    private CompassBar() {}

    private static final Logger LOG = LogManager.getLogger("PhantomZHud/Compass");
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float VIEW = 110f, BAND = 8f, MID = 4f;
    private static final int GOLD_HI = argb(240, 204, 120, 255), BAR = argb(8, 8, 12, 255);
    /** Distance from an ornament's centre to the bar end: the inner satellite diamond's tip touches the bar. */
    private static final float KNOT_GAP = 6.9f;

    static void render(GuiGraphics g, Font font, Player p, int sw, float scale, float partial) {
        float W = HudConfig.COMPASS_WIDTH.get();
        g.pose().pushPose();
        g.pose().translate(sw / 2f, 5f, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();

        float half = W / 2f, ppd = W / VIEW;
        float heading = (Mth.wrapDegrees(p.getViewYRot(partial) + 180f) + 360f) % 360f;

        // translucent body (the world stays visible through it) with a thin gold rail on the top and bottom edge
        rect(m, withAlpha(BAR, 120), -half, 0f, half, BAND);
        rect(m, withAlpha(GOLD_HI, 228), -half, -0.05f, half, 0.7f);
        rect(m, withAlpha(GOLD_HI, 228), -half, BAND - 0.7f, half, BAND + 0.05f);

        // letters and small ticks (they fade out before the ornaments)
        for (int deg = 0; deg < 360; deg += 5) {
            float d = Mth.wrapDegrees(deg - heading);
            if (Math.abs(d) > VIEW / 2f) continue;
            float x = d * ppd;
            float edgeFade = PlayerBlock.clamp01((half - Math.abs(x) - 2f) / 10f);
            if (edgeFade <= 0.02f) continue;
            if (deg % 45 == 0) {
                String s = NAMES[deg / 45];
                boolean cardinal = deg % 90 == 0;
                float ts = cardinal ? 0.82f : 0.6f;
                g.pose().pushPose();
                g.pose().translate(x, MID - 3.5f * ts, 0);
                g.pose().scale(ts, ts, 1f);
                g.drawString(font, s, Math.round(-font.width(s) / 2f), 0, withAlpha(BONE, Math.round(255 * edgeFade)), true);
                g.pose().popPose();
            } else if (deg % 15 == 0) {
                line(g.pose().last().pose(), withAlpha(CREAM, Math.round(170 * edgeFade)), x, MID - 1.1f, x, MID + 1.1f, 0.55f);
            } else {
                line(g.pose().last().pose(), withAlpha(CREAM, Math.round(80 * edgeFade)), x, MID - 0.6f, x, MID + 0.6f, 0.45f);
            }
        }

        // markers from Remnants Minimap
        if (HudConfig.COMPASS_MARKERS.get()) Markers.draw(g, font, p, heading, ppd);

        m = g.pose().last().pose();
        knot(m, -half - KNOT_GAP);
        knot(m, half + KNOT_GAP);
        g.pose().popPose();
    }

    /**
     * The end ornament: a centre diamond with four satellite diamonds around it, taller than the bar. Symmetric, so the
     * same shape serves both ends.
     */
    private static void knot(Matrix4f m, float cx) {
        final float big = 3.3f, small = 2.3f, dist = 4.6f;
        float[][] sat = {{dist, 0f}, {-dist, 0f}, {0f, -dist}, {0f, dist}};
        for (float[] o : sat) diamondFill(m, withAlpha(BAR, 150), cx + o[0], MID + o[1], small, small);
        diamondFill(m, withAlpha(BAR, 200), cx, MID, big, big);
        for (float[] o : sat) diamondLine(m, withAlpha(GOLD, 235), cx + o[0], MID + o[1], small, small, 0.7f);
        diamondLine(m, GOLD_HI, cx, MID, big, big, 0.9f);
        diamondFill(m, GOLD_HI, cx, MID, 1.2f, 1.2f);
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
                    diamondFill(m, INK, x, MID, 3.7f, 3.7f);
                    diamondFill(m, col, x, MID, 2.7f, 2.7f);
                    diamondLine(m, withAlpha(GOLD, 230), x, MID, 3.7f, 3.7f, 0.6f);
                    String s = dist >= 1000 ? String.format("%.1fk", dist / 1000.0) : Math.round(dist) + "m";
                    g.pose().pushPose();
                    g.pose().translate(x, BAND + 3.4f, 0);
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

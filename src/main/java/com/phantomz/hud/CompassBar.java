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
 * Compass built after Skyrim's: a long black bar with a fine double rail (gold here, silver there), small letters
 * and tick marks running along it, and a pointed crest with a woven centre on each end. Nothing else: no pointer, no
 * degree readout. Remnants Minimap markers sit on the bar with their distance underneath.
 */
final class CompassBar {
    private CompassBar() {}

    private static final Logger LOG = LogManager.getLogger("PhantomZHud/Compass");
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float VIEW = 110f, BAND = 14f, MID = 7f;
    private static final int GOLD_HI = argb(240, 204, 120, 255), BAR = argb(8, 8, 10, 255);

    static void render(GuiGraphics g, Font font, Player p, int sw, float scale, float partial) {
        float W = HudConfig.COMPASS_WIDTH.get();
        g.pose().pushPose();
        g.pose().translate(sw / 2f, 5f, 0);
        g.pose().scale(scale, scale, 1f);
        Matrix4f m = g.pose().last().pose();

        float half = W / 2f, ppd = W / VIEW;
        float heading = (Mth.wrapDegrees(p.getViewYRot(partial) + 180f) + 360f) % 360f;

        // black bar with a thin dark outline, the outer gold rail and a fainter inner rail (top and bottom)
        rect(m, withAlpha(INK, 255), -half, -1.4f, half, BAND + 1.4f);
        rect(m, withAlpha(BAR, 244), -half, 0f, half, BAND);
        rect(m, GOLD_HI, -half, -0.2f, half, 0.9f);
        rect(m, GOLD_HI, -half, BAND - 0.9f, half, BAND + 0.2f);
        rect(m, withAlpha(GOLD, 120), -half, 2.3f, half, 2.8f);
        rect(m, withAlpha(GOLD, 120), -half, BAND - 2.8f, half, BAND - 2.3f);

        // letters and small ticks (they slide under the crests)
        for (int deg = 0; deg < 360; deg += 5) {
            float d = Mth.wrapDegrees(deg - heading);
            if (Math.abs(d) > VIEW / 2f) continue;
            float x = d * ppd;
            float edgeFade = PlayerBlock.clamp01((half - Math.abs(x) - 2f) / 10f);
            if (edgeFade <= 0.02f) continue;
            if (deg % 45 == 0) {
                String s = NAMES[deg / 45];
                boolean cardinal = deg % 90 == 0;
                float ts = cardinal ? 1.05f : 0.78f;
                g.pose().pushPose();
                g.pose().translate(x, MID - 3.5f * ts, 0);
                g.pose().scale(ts, ts, 1f);
                g.drawString(font, s, Math.round(-font.width(s) / 2f), 0, withAlpha(BONE, Math.round(255 * edgeFade)), true);
                g.pose().popPose();
            } else if (deg % 15 == 0) {
                line(g.pose().last().pose(), withAlpha(CREAM, Math.round(150 * edgeFade)), x, MID - 1.6f, x, MID + 1.6f, 0.6f);
            } else {
                line(g.pose().last().pose(), withAlpha(CREAM, Math.round(70 * edgeFade)), x, MID - 0.8f, x, MID + 0.8f, 0.5f);
            }
        }

        // markers from Remnants Minimap
        if (HudConfig.COMPASS_MARKERS.get()) Markers.draw(g, font, p, heading, ppd);

        m = g.pose().last().pose();
        crest(m, -half, -1);
        crest(m, half, 1);
        g.pose().popPose();
    }

    /** Pointed crest on one end (dir -1 left, +1 right): a hair taller than the bar, flat shoulders, a woven centre. */
    private static void crest(Matrix4f m, float ex, int dir) {
        float rise = 1.7f, j = ex - dir * 0.6f, sx = ex + dir * 7.5f, tip = ex + dir * 17.5f, bot = BAND + rise;
        float[] o = {j, -rise, sx, -rise, tip, MID, sx, bot, j, bot};
        float[] dark = {j, -rise - 1.2f, sx + dir * 0.6f, -rise - 1.2f, tip + dir * 1.8f, MID, sx + dir * 0.6f, bot + 1.2f, j, bot + 1.2f};
        poly(m, withAlpha(INK, 255), dark);
        poly(m, withAlpha(BAR, 255), o);
        for (int i = 0; i < o.length / 2; i++) {
            int n = (i + 1) % (o.length / 2);
            line(m, GOLD_HI, o[i * 2], o[i * 2 + 1], o[n * 2], o[n * 2 + 1], 1.1f);
        }
        float cx = ex + dir * 8.2f;
        // woven centre: a small lattice of diamonds
        float[][] cell = {{0f, 0f}, {-3.6f, 0f}, {3.6f, 0f}, {0f, -3.9f}, {0f, 3.9f}};
        for (float[] c : cell) {
            boolean mid = c[0] == 0f && c[1] == 0f;
            diamondLine(m, withAlpha(GOLD, mid ? 255 : 190), cx + c[0], MID + c[1], mid ? 2.3f : 1.7f, mid ? 2.3f : 1.7f, 0.6f);
        }
        diamondFill(m, GOLD_HI, cx, MID, 0.9f, 0.9f);
        for (int sy = -1; sy <= 1; sy += 2) diamondFill(m, withAlpha(GOLD, 200), cx - dir * 6.1f, MID + sy * 3.9f, 0.55f, 0.55f);
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
                    diamondFill(m, INK, x, MID, 4.6f, 4.6f);
                    diamondFill(m, col, x, MID, 3.4f, 3.4f);
                    diamondLine(m, withAlpha(GOLD, 230), x, MID, 4.6f, 4.6f, 0.7f);
                    String s = dist >= 1000 ? String.format("%.1fk", dist / 1000.0) : Math.round(dist) + "m";
                    g.pose().pushPose();
                    g.pose().translate(x, BAND + 5.2f, 0);
                    g.pose().scale(0.55f, 0.55f, 1f);
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

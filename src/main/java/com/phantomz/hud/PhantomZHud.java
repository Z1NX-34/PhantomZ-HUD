package com.phantomz.hud;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(PhantomZHud.MODID)
public final class PhantomZHud {
    public static final String MODID = "phantomzhud";

    public PhantomZHud() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, HudConfig.SPEC, "phantomz_hud-client.toml");
    }
}

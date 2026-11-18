package com.winlator.cmod.inputcontrols;

import android.content.Context;

import com.winlator.cmod.R;

public enum ControlStylePreset {
    // The default: glass after Madeira's touch controls. Profiles saved with a removed style (the former default
    // "WINLATOR_MALI", Cyberpunk, Retro Arcade, Stealth) load as this one (see parse).
    GLASS("WinMali Default", 0, R.string.control_style_glass),
    XBOX("Xbox Edition", 1, R.string.control_style_xbox),
    PLAYSTATION("PlayStation Edition", 2, R.string.control_style_playstation);

    public final String title;
    public final int id;
    private final int titleRes;

    ControlStylePreset(String title, int id, int titleRes) {
        this.title = title;
        this.id = id;
        this.titleRes = titleRes;
    }

    public String getTitle(Context context) {
        return context.getString(titleRes);
    }

    public static String[] titles(Context context) {
        ControlStylePreset[] presets = values();
        String[] titles = new String[presets.length];
        for (int i = 0; i < presets.length; i++) titles[i] = presets[i].getTitle(context);
        return titles;
    }

    public static ControlStylePreset parse(String name) {
        if (name == null || name.isEmpty()) return GLASS;
        try {
            return valueOf(name.trim().toUpperCase().replace(" ", "_").replace("-", "_"));
        } catch (IllegalArgumentException e) {
            for (ControlStylePreset p : values()) {
                if (p.name().equalsIgnoreCase(name) || p.title.equalsIgnoreCase(name)) return p;
            }
            return GLASS;
        }
    }
}

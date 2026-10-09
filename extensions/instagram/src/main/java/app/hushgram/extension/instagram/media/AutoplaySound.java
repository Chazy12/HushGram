/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Ensures videos in feed and stories start with sound on immediately.
 *
 * <p>By default, Instagram mutes feed videos and some stories until a user taps to unmute.
 * When enabled, this helper unmutes the player as soon as playback starts.
 */
public final class AutoplaySound {
    private static final String SOURCE = "AutoplaySound";
    private static final Map<Object, Boolean> UNMUTED_PLAYERS = Collections.synchronizedMap(new WeakHashMap<>());

    private AutoplaySound() {}

    /**
     * Checks if the start with sound feature is enabled in settings.
     */
    public static boolean isEnabled() {
        return Utils.settingsReady() && Settings.START_WITH_SOUND.get();
    }

    /**
     * Called whenever a video player starts (feed, story, or reels).
     *
     * @param player The video player instance (e.g. IgVideoPlayerImpl or IgGrootPlayer)
     */
    public static void onPlayerStart(Object player) {
        if (player == null || !isEnabled()) return;
        try {
            if (UNMUTED_PLAYERS.containsKey(player)) return;
            UNMUTED_PLAYERS.put(player, Boolean.TRUE);

            unmute(player);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SETTINGS, "start with sound", t);
        }
    }

    /**
     * Attempts to unmute the given player instance through reflection.
     */
    private static void unmute(Object player) {
        Class<?> clazz = player.getClass();
        
        // 1. Try finding and calling setVolume(float) or setVolume(1.0f)
        for (Method method : clazz.getMethods()) {
            if (method.getName().equalsIgnoreCase("setVolume") && method.getParameterTypes().length == 1) {
                try {
                    Class<?> paramType = method.getParameterTypes()[0];
                    if (paramType == float.class || paramType == Float.class) {
                        method.invoke(player, 1.0f);
                        Logger.printDebug(() -> "AutoplaySound: set volume to 1.0 on " + clazz.getSimpleName());
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 2. Try finding and calling unmute()
        for (Method method : clazz.getMethods()) {
            if (method.getName().equalsIgnoreCase("unmute") && method.getParameterTypes().length == 0) {
                try {
                    method.invoke(player);
                    Logger.printDebug(() -> "AutoplaySound: called unmute() on " + clazz.getSimpleName());
                    return;
                } catch (Throwable ignored) {}
            }
        }

        // 3. Try finding and calling setMuted(boolean false)
        for (Method method : clazz.getMethods()) {
            if (method.getName().equalsIgnoreCase("setMuted") && method.getParameterTypes().length == 1) {
                try {
                    Class<?> paramType = method.getParameterTypes()[0];
                    if (paramType == boolean.class || paramType == Boolean.class) {
                        method.invoke(player, false);
                        Logger.printDebug(() -> "AutoplaySound: setMuted(false) on " + clazz.getSimpleName());
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
}

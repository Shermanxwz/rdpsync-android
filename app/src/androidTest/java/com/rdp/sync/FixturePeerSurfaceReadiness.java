package com.rdp.sync;

import java.util.Map;
import java.util.Objects;

/** Pure fixture prerequisite only; does not inject input, sleep, or change the peer. */
public final class FixturePeerSurfaceReadiness {
    private FixturePeerSurfaceReadiness() {}
    private static long number(Map<String, ?> value, String key) {
        Object found = value.get(key);
        return found instanceof Number ? ((Number) found).longValue() : -1;
    }
    public static boolean ready(Map<String, ?> current, Map<String, ?> before, boolean gnome,
            long nativeGeneration, long expectedGeneration, long now, long deadline,
            int remoteWidth, int remoteHeight) {
        long width = number(current, "width"), height = number(current, "height");
        if (now >= deadline || expectedGeneration <= 0 || nativeGeneration != expectedGeneration ||
                !Boolean.TRUE.equals(current.get("ready")) || width <= 400 || height <= 300 ||
                width != remoteWidth || height != remoteHeight) return false;
        if (!gnome) return true;
        long generation = number(current, "presentation_generation");
        return before != null && Boolean.TRUE.equals(current.get("mapped")) &&
                Boolean.TRUE.equals(current.get("visible")) && Boolean.TRUE.equals(current.get("fullscreen")) &&
                generation > 0 && number(before, "presentation_generation") >= 0 &&
                generation >= number(before, "presentation_generation") &&
                number(current, "painted_generation") == generation &&
                number(current, "after_paint_generation") == generation &&
                number(current, "monitor_width") == width && number(current, "monitor_height") == height &&
                current.get("session_id") instanceof String && !((String) current.get("session_id")).isEmpty() &&
                Objects.equals(current.get("session_id"), before.get("session_id")) &&
                number(current, "desktop_pid") > 0 && number(current, "desktop_pid") == number(before, "desktop_pid");
    }
}

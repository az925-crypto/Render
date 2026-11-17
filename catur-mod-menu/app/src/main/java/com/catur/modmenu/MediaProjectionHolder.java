package com.catur.modmenu;

import android.media.projection.MediaProjection;

/** Holds the screen-capture permission object between Activity and Service. */
final class MediaProjectionHolder {
    private static volatile MediaProjection projection;

    static void set(MediaProjection p) { projection = p; }
    static MediaProjection get() { return projection; }
    static void clear() { projection = null; }

    private MediaProjectionHolder() {}
}

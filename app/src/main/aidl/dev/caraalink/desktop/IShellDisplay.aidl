// Runs in the Shizuku helper process (shell user). Owns the car-sized display
// that other apps open on, and puts touches and keys on it.
package dev.caraalink.desktop;

import android.view.Surface;

interface IShellDisplay {
    /** Creates the display rendering into [surface]; returns its display id, or -1. */
    int create(in Surface surface, int width, int height, int dpi) = 1;
    void resize(int width, int height, int dpi) = 2;
    void setSurface(in Surface surface) = 3;
    /** Removes the display; apps on it close. */
    void release() = 4;
    /** Opens "package/class" on the display; returns null, or what went wrong. */
    String start(String component) = 5;
    /** A MotionEvent action at display pixels. */
    oneway void touch(int action, float x, float y) = 6;
    oneway void key(int keyCode) = 7;
    /** Keyboard on this display (true) or on the phone (false); returns null, or what went wrong. */
    String setImeLocal(boolean local) = 8;
    /** Shizuku's own code for ending the helper process. */
    void destroy() = 16777114;
}

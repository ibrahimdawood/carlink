package dev.caraalink.mirror

/**
 * Maps a touch on the car's video frame to the phone screen while mirroring.
 *
 * The phone screen is scaled into the frame without stretching (letterboxed),
 * centred, with black bars on the long sides. A touch on a bar hits nothing.
 */
object MirrorTouch {

    data class Point(val x: Float, val y: Float)

    /**
     * [x],[y] in frame pixels ([frameW]x[frameH]); returns phone pixels
     * ([phoneW]x[phoneH], the phone's current orientation), or null on a bar.
     */
    fun map(frameW: Int, frameH: Int, phoneW: Int, phoneH: Int, x: Float, y: Float): Point? {
        if (frameW <= 0 || frameH <= 0 || phoneW <= 0 || phoneH <= 0) return null
        val scale = minOf(frameW.toFloat() / phoneW, frameH.toFloat() / phoneH)
        val offX = (frameW - phoneW * scale) / 2f
        val offY = (frameH - phoneH * scale) / 2f
        val px = (x - offX) / scale
        val py = (y - offY) / scale
        // Half a frame pixel of slack so a touch on the picture's very edge still counts.
        val slack = 0.5f / scale
        if (px < -slack || py < -slack || px > phoneW + slack || py > phoneH + slack) return null
        return Point(px.coerceIn(0f, phoneW - 1f), py.coerceIn(0f, phoneH - 1f))
    }
}

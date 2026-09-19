package com.meshcentral.agent

import kotlin.math.abs
import kotlin.math.max

// MeshCentral remote desktop input commands. These arrive on a usage 2 (desktop)
// tunnel as binary messages of the form [short command][short size][payload].
internal const val MNG_KVM_KEY = 1
internal const val MNG_KVM_MOUSE = 2
internal const val MNG_KVM_KEY_UNICODE = 85
internal const val MNG_KVM_INPUT_LOCK = 87

// MeshCentral reuses the Windows MOUSEEVENTF_* values in the mouse message.
internal const val MOUSE_MOVE = 0x0001
internal const val MOUSE_LEFT_DOWN = 0x0002
internal const val MOUSE_LEFT_UP = 0x0004
internal const val MOUSE_RIGHT_DOWN = 0x0008
internal const val MOUSE_RIGHT_UP = 0x0010
internal const val MOUSE_MIDDLE_DOWN = 0x0020
internal const val MOUSE_MIDDLE_UP = 0x0040
internal const val MOUSE_WHEEL = 0x0800

// Key actions used by the key and unicode key messages.
internal const val KEY_ACTION_DOWN = 0
internal const val KEY_ACTION_UP = 1
internal const val KEY_ACTION_PRESS = 2

// Browser key codes the operator's keyboard can send. Android has no way for an
// ordinary application to inject key events, so only the codes that map onto an
// accessibility action are listed here.
internal const val KEYCODE_BACKSPACE = 8
internal const val KEYCODE_ENTER = 13
internal const val KEYCODE_ESCAPE = 27
internal const val KEYCODE_PAGE_UP = 33
internal const val KEYCODE_PAGE_DOWN = 34
internal const val KEYCODE_HOME = 36
internal const val KEYCODE_DELETE = 46
internal const val KEYCODE_LEFT_META = 91
internal const val KEYCODE_RIGHT_META = 92

/** A decoded remote desktop input message. */
internal sealed class RemoteInputEvent {
    data class Mouse(val buttons: Int, val x: Int, val y: Int, val wheel: Int) : RemoteInputEvent()
    data class Key(val action: Int, val keyCode: Int) : RemoteInputEvent()
    data class UnicodeKey(val action: Int, val character: Int) : RemoteInputEvent()
    data class InputLock(val locked: Boolean) : RemoteInputEvent()
}

/** A system-wide action that the accessibility service can perform. */
enum class RemoteGlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, LOCK_SCREEN }

/** A gesture recognised from a stream of mouse messages, in remote display coordinates. */
internal sealed class RemoteInputGesture {
    data class Tap(val x: Int, val y: Int) : RemoteInputGesture()
    data class LongPress(val x: Int, val y: Int) : RemoteInputGesture()
    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Long
    ) : RemoteInputGesture()
    data class Scroll(val x: Int, val y: Int, val wheel: Int) : RemoteInputGesture()
    data class Global(val action: RemoteGlobalAction) : RemoteInputGesture()
}

private fun readUInt16(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}

private fun readInt16(data: ByteArray, offset: Int): Int {
    val value = readUInt16(data, offset)
    return if (value >= 0x8000) value - 0x10000 else value
}

/**
 * Decode a binary remote desktop input message. Returns null when the command is
 * not an input command or the message is too short to be valid.
 */
internal fun decodeRemoteInputCommand(cmd: Int, data: ByteArray): RemoteInputEvent? {
    when (cmd) {
        MNG_KVM_KEY -> {
            // [short 1][short 6][byte action][byte key code]
            if (data.size < 6) return null
            val action = data[4].toInt() and 0xFF
            if (action > KEY_ACTION_PRESS) return null
            return RemoteInputEvent.Key(action, data[5].toInt() and 0xFF)
        }
        MNG_KVM_MOUSE -> {
            // [short 2][short 10 or 12][short buttons][short x][short y]([short wheel])
            if (data.size < 10) return null
            val wheel = if (data.size >= 12) readInt16(data, 10) else 0
            return RemoteInputEvent.Mouse(
                readUInt16(data, 4),
                readUInt16(data, 6),
                readUInt16(data, 8),
                wheel
            )
        }
        MNG_KVM_KEY_UNICODE -> {
            // MeshCentral sends [short 85][short 6][short unicode]. Some builds also
            // send a key action, which makes the message 8 bytes long.
            return when {
                data.size >= 8 -> RemoteInputEvent.UnicodeKey(
                    data[4].toInt() and 0xFF,
                    readUInt16(data, 6)
                )
                data.size >= 6 -> RemoteInputEvent.UnicodeKey(
                    KEY_ACTION_PRESS,
                    readUInt16(data, 4)
                )
                else -> null
            }
        }
        MNG_KVM_INPUT_LOCK -> {
            // [short 87][short 5][byte locked]
            if (data.size < 5) return null
            return RemoteInputEvent.InputLock((data[4].toInt() and 0xFF) != 0)
        }
    }
    return null
}

/**
 * Turns the operator's mouse messages into Android gestures. A press followed by a
 * release near the same point is a tap, a longer press is a long press, and a press
 * that moves is a swipe over the same amount of time the operator took.
 */
internal class MouseGestureRecognizer(
    private val tapSlop: Int = 16,
    private val longPressMs: Long = 500,
    private val minSwipeMs: Long = 60,
    private val maxGestureMs: Long = 10000
) {
    private var pressed = false
    private var startX = 0
    private var startY = 0
    private var lastX = 0
    private var lastY = 0
    private var startTime = 0L
    private var moved = false

    fun reset() {
        pressed = false
        moved = false
    }

    fun onMouse(event: RemoteInputEvent.Mouse, nowMs: Long): RemoteInputGesture? {
        // A wheel message scrolls whatever is under the pointer.
        if (((event.buttons and MOUSE_WHEEL) != 0) && (event.wheel != 0)) {
            return RemoteInputGesture.Scroll(event.x, event.y, event.wheel)
        }

        if ((event.buttons and MOUSE_LEFT_DOWN) != 0) {
            pressed = true
            moved = false
            startX = event.x
            startY = event.y
            lastX = event.x
            lastY = event.y
            startTime = nowMs
            return null
        }

        if ((event.buttons and MOUSE_LEFT_UP) != 0) {
            if (!pressed) return null
            pressed = false

            // Some clients send the release without coordinates. Fall back to the
            // last point seen while the button was held down.
            var endX = event.x
            var endY = event.y
            if (moved && (endX == 0) && (endY == 0)) {
                endX = lastX
                endY = lastY
            }

            val elapsed = (nowMs - startTime).coerceIn(1, maxGestureMs)
            val distance = max(abs(endX - startX), abs(endY - startY))
            return when {
                distance > tapSlop -> RemoteInputGesture.Swipe(
                    startX,
                    startY,
                    endX,
                    endY,
                    elapsed.coerceAtLeast(minSwipeMs)
                )
                elapsed >= longPressMs -> RemoteInputGesture.LongPress(startX, startY)
                else -> RemoteInputGesture.Tap(startX, startY)
            }
        }

        // Android has no pointer to move, so the right and middle buttons are the
        // only way to reach the navigation bar from a plain mouse.
        if ((event.buttons and MOUSE_RIGHT_UP) != 0) {
            return RemoteInputGesture.Global(RemoteGlobalAction.BACK)
        }
        if ((event.buttons and MOUSE_MIDDLE_UP) != 0) {
            return RemoteInputGesture.Global(RemoteGlobalAction.HOME)
        }

        if (pressed && ((event.buttons and MOUSE_MOVE) != 0)) {
            lastX = event.x
            lastY = event.y
            moved = true
        }
        return null
    }
}

/**
 * Convert a coordinate in the remote display image back to a device pixel. The
 * display is sent to the server scaled by [scalingLevel] / 1024.
 */
internal fun descaleDesktopCoordinate(value: Int, scalingLevel: Int): Int {
    if ((scalingLevel <= 0) || (scalingLevel == 1024)) return value
    return (value.toLong() * 1024L / scalingLevel.toLong()).toInt()
}

/** Map a browser key code onto the global action it should perform, if any. */
internal fun globalActionForKeyCode(keyCode: Int): RemoteGlobalAction? {
    return when (keyCode) {
        KEYCODE_ESCAPE -> RemoteGlobalAction.BACK
        KEYCODE_HOME, KEYCODE_LEFT_META, KEYCODE_RIGHT_META -> RemoteGlobalAction.HOME
        else -> null
    }
}

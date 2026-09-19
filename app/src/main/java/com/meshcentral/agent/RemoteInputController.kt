package com.meshcentral.agent

import android.content.res.Resources
import android.os.SystemClock

// Set from the "Remote Input" application setting. Even when this is on, input is
// only possible while the device user also has the accessibility service enabled.
var g_remoteInput: Boolean = false

/**
 * Turns remote desktop input messages into Android gestures and text entry.
 *
 * Coordinates arrive in the coordinate space of the image that was sent to the
 * server, which may be scaled down, so they are scaled back up before use.
 */
internal object RemoteInputController {
    private val recognizer = MouseGestureRecognizer()

    /** Reason why input is unavailable, or null when it is ready to use. */
    fun unavailableReason(): String? {
        if (!g_remoteInput) return "Remote input is turned off in the agent settings"
        val service = g_remoteInputService
            ?: return "The MeshCentral remote input accessibility service is not enabled"
        if (!service.canDispatchGestures()) return "Remote input requires Android 7.0 or later"
        return null
    }

    fun isAvailable(): Boolean {
        return unavailableReason() == null
    }

    /** Describe the current input state for the console. */
    fun statusText(): String {
        val reason = unavailableReason()
        if (reason != null) return "Remote input: unavailable. $reason."
        return "Remote input: ready."
    }

    fun reset() {
        recognizer.reset()
    }

    fun handle(event: RemoteInputEvent) {
        val service = g_remoteInputService
        if ((service == null) || !g_remoteInput) return
        try {
            when (event) {
                is RemoteInputEvent.Mouse -> handleMouse(service, event)
                is RemoteInputEvent.Key -> handleKey(service, event)
                is RemoteInputEvent.UnicodeKey -> handleUnicodeKey(service, event)
                is RemoteInputEvent.InputLock -> {
                    // Android cannot block input on the physical device, so this is
                    // accepted and ignored rather than reported as an error.
                }
            }
        } catch (ex: Exception) {
            println("RemoteInputController error: $ex")
        }
    }

    private fun handleMouse(service: RemoteInputService, event: RemoteInputEvent.Mouse) {
        val gesture = recognizer.onMouse(event, SystemClock.elapsedRealtime()) ?: return
        when (gesture) {
            is RemoteInputGesture.Tap -> {
                val (x, y) = toDevicePoint(gesture.x, gesture.y)
                service.tap(x, y)
            }
            is RemoteInputGesture.LongPress -> {
                val (x, y) = toDevicePoint(gesture.x, gesture.y)
                service.longPress(x, y)
            }
            is RemoteInputGesture.Swipe -> {
                val (fromX, fromY) = toDevicePoint(gesture.fromX, gesture.fromY)
                val (toX, toY) = toDevicePoint(gesture.toX, gesture.toY)
                service.swipe(fromX, fromY, toX, toY, gesture.durationMs)
            }
            is RemoteInputGesture.Scroll -> {
                val (x, y) = toDevicePoint(gesture.x, gesture.y)
                // A wheel turn away from the operator scrolls the content up, which
                // on a touch screen means dragging a finger down.
                val distance = SCROLL_DISTANCE_PX
                val target = if (gesture.wheel > 0) y + distance else y - distance
                service.swipe(x, y, x, clampY(target), SCROLL_DURATION_MS)
            }
            is RemoteInputGesture.Global -> service.performRemoteAction(gesture.action)
        }
    }

    private fun handleKey(service: RemoteInputService, event: RemoteInputEvent.Key) {
        // Only act once per key press, on the way down.
        if (event.action == KEY_ACTION_UP) return

        val globalAction = globalActionForKeyCode(event.keyCode)
        if (globalAction != null) {
            service.performRemoteAction(globalAction)
            return
        }

        when (event.keyCode) {
            KEYCODE_BACKSPACE, KEYCODE_DELETE -> service.deleteLastCharacter()
            KEYCODE_ENTER -> service.pressEnter()
            KEYCODE_PAGE_UP -> scrollPage(service, up = true)
            KEYCODE_PAGE_DOWN -> scrollPage(service, up = false)
        }
    }

    private fun handleUnicodeKey(service: RemoteInputService, event: RemoteInputEvent.UnicodeKey) {
        if (event.action == KEY_ACTION_UP) return
        val character = event.character
        when {
            character == 8 -> service.deleteLastCharacter()
            (character == 10) || (character == 13) -> service.pressEnter()
            character < 32 -> return // Other control characters have no Android equivalent
            else -> service.typeText(String(Character.toChars(character)))
        }
    }

    private fun scrollPage(service: RemoteInputService, up: Boolean) {
        val width = displayWidth()
        val height = displayHeight()
        val x = width / 2
        val from = if (up) height / 4 else (height * 3) / 4
        val to = if (up) (height * 3) / 4 else height / 4
        service.swipe(x, from, x, to, SCROLL_DURATION_MS)
    }

    // Console entry points. These take device pixels rather than remote display
    // coordinates, since the operator types them by hand.

    fun performConsoleAction(action: RemoteGlobalAction): String {
        val service = consoleService()
        if (service == null) return unavailableReason() ?: UNAVAILABLE
        return if (service.performRemoteAction(action)) "Ok" else "The device refused the action"
    }

    fun consoleTap(x: Int, y: Int): String {
        val service = consoleService()
        if (service == null) return unavailableReason() ?: UNAVAILABLE
        return if (service.tap(clampX(x), clampY(y))) "Ok" else "The device refused the gesture"
    }

    fun consoleSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): String {
        val service = consoleService()
        if (service == null) return unavailableReason() ?: UNAVAILABLE
        return if (service.swipe(clampX(fromX), clampY(fromY), clampX(toX), clampY(toY), durationMs)) {
            "Ok"
        } else {
            "The device refused the gesture"
        }
    }

    fun consoleType(text: String): String {
        val service = consoleService()
        if (service == null) return unavailableReason() ?: UNAVAILABLE
        if (!service.hasFocusedTextField()) return "No text field has focus on the device"
        return if (service.typeText(text)) "Ok" else "The device refused the text"
    }

    private fun consoleService(): RemoteInputService? {
        if (unavailableReason() != null) return null
        return g_remoteInputService
    }

    private fun toDevicePoint(x: Int, y: Int): Pair<Int, Int> {
        val deviceX = descaleDesktopCoordinate(x, g_desktop_scalingLevel)
        val deviceY = descaleDesktopCoordinate(y, g_desktop_scalingLevel)
        return Pair(clampX(deviceX), clampY(deviceY))
    }

    private fun displayWidth(): Int {
        val captureWidth = g_ScreenCaptureService?.mWidth ?: 0
        if (captureWidth > 0) return captureWidth
        return Resources.getSystem().displayMetrics.widthPixels
    }

    private fun displayHeight(): Int {
        val captureHeight = g_ScreenCaptureService?.mHeight ?: 0
        if (captureHeight > 0) return captureHeight
        return Resources.getSystem().displayMetrics.heightPixels
    }

    private fun clampX(value: Int): Int {
        return value.coerceIn(0, (displayWidth() - 1).coerceAtLeast(0))
    }

    private fun clampY(value: Int): Int {
        return value.coerceIn(0, (displayHeight() - 1).coerceAtLeast(0))
    }

    private const val UNAVAILABLE = "Remote input is unavailable"
    private const val SCROLL_DISTANCE_PX = 400
    private const val SCROLL_DURATION_MS = 250L
}

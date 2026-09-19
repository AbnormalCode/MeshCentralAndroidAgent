package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// The connected remote input service, or null when the device user has not
// enabled it in the Android accessibility settings.
var g_remoteInputService: RemoteInputService? = null

/**
 * Performs remote taps, swipes and text entry on behalf of a MeshCentral operator.
 *
 * Android does not let an ordinary application inject input events, so control is
 * implemented with the public accessibility gesture API. The device user has to
 * turn this service on in Android's accessibility settings, and can turn it off at
 * any time; the agent never enables it by itself.
 */
class RemoteInputService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        g_remoteInputService = this
        meshAgent?.sendConsoleResponse("Remote input service connected", null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The agent does not read the screen content, it only sends input.
    }

    override fun onInterrupt() {
        // Nothing to interrupt, input is dispatched one gesture at a time.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (g_remoteInputService === this) g_remoteInputService = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (g_remoteInputService === this) g_remoteInputService = null
        super.onDestroy()
    }

    /** True when this Android version can dispatch gestures. */
    fun canDispatchGestures(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    fun tap(x: Int, y: Int): Boolean {
        return dispatchStroke(pathBetween(x, y, x, y), TAP_DURATION_MS)
    }

    fun longPress(x: Int, y: Int): Boolean {
        return dispatchStroke(pathBetween(x, y, x, y), LONG_PRESS_DURATION_MS)
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        return dispatchStroke(
            pathBetween(fromX, fromY, toX, toY),
            durationMs.coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS)
        )
    }

    /** Perform a system navigation action such as back or home. */
    fun performRemoteAction(action: RemoteGlobalAction): Boolean {
        val id = when (action) {
            RemoteGlobalAction.BACK -> GLOBAL_ACTION_BACK
            RemoteGlobalAction.HOME -> GLOBAL_ACTION_HOME
            RemoteGlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
            RemoteGlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            RemoteGlobalAction.LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
                GLOBAL_ACTION_LOCK_SCREEN
            }
        }
        return try {
            performGlobalAction(id)
        } catch (ex: Exception) {
            false
        }
    }

    /** Append text to the focused text field. */
    fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        return withFocusedEditable { node ->
            val current = node.text?.toString() ?: ""
            setNodeText(node, current + text)
        }
    }

    /** Remove the last character of the focused text field. */
    fun deleteLastCharacter(): Boolean {
        return withFocusedEditable { node ->
            val current = node.text?.toString() ?: ""
            if (current.isEmpty()) false else setNodeText(node, current.dropLast(1))
        }
    }

    /** Trigger the editor action of the focused text field, such as search or send. */
    fun pressEnter(): Boolean {
        return withFocusedEditable { node ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                val current = node.text?.toString() ?: ""
                setNodeText(node, current + "\n")
            }
        }
    }

    /** True when a text field currently has input focus. */
    fun hasFocusedTextField(): Boolean {
        return withFocusedEditable { true }
    }

    @Suppress("DEPRECATION")
    private fun <T> withFocusedEditableOrNull(block: (AccessibilityNodeInfo) -> T): T? {
        val node = try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (ex: Exception) {
            null
        } ?: return null
        return try {
            if (node.isEditable) block(node) else null
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                try {
                    node.recycle()
                } catch (ex: Exception) {
                }
            }
        }
    }

    private fun withFocusedEditable(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        return withFocusedEditableOrNull(block) ?: false
    }

    private fun setNodeText(node: AccessibilityNodeInfo, value: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value
        )
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false

        // Leave the caret at the end so the next character lands in the right place.
        val selection = Bundle()
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, value.length)
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        } catch (ex: Exception) {
        }
        return true
    }

    private fun pathBetween(fromX: Int, fromY: Int, toX: Int, toY: Int): Path {
        val path = Path()
        path.moveTo(fromX.toFloat(), fromY.toFloat())
        if ((fromX == toX) && (fromY == toY)) {
            // A stroke needs a path with a length, so nudge the end point by a pixel.
            path.lineTo(toX + 1f, toY + 1f)
        } else {
            path.lineTo(toX.toFloat(), toY.toFloat())
        }
        return path
    }

    private fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (ex: Exception) {
            println("RemoteInputService dispatch failed: $ex")
            false
        }
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val LONG_PRESS_DURATION_MS = 700L
        private const val MIN_GESTURE_DURATION_MS = 50L
        private const val MAX_GESTURE_DURATION_MS = 10000L

        /** True when the device user has enabled the service in Android settings. */
        fun isServiceEnabled(context: Context): Boolean {
            val component = ComponentName(context.applicationContext, RemoteInputService::class.java)
            val longName = component.flattenToString()
            val shortName = component.flattenToShortString()
            val enabled = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (ex: Exception) {
                null
            } ?: return false
            return enabled.split(':').any {
                it.equals(longName, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
            }
        }

        /** Open the Android accessibility settings so the user can enable the service. */
        fun openAccessibilitySettings(context: Context): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (ex: Exception) {
                false
            }
        }
    }
}

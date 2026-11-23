package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.util.ArrayDeque

/** Local opt-in, Accessibility approval and current screen sharing are all required. */
class RemoteControlService : AccessibilityService() {
    private data class Pending(val source: MeshTunnel, val action: DesktopAction, val geometry: DesktopGeometry)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Pending>()
    private var dispatching = false
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "pref_remotecontrol") clearRemoteState()
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { clearRemoteState() }
    }
    override fun onCreate() {
        super.onCreate()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun clearRemoteState() {
        pending.clear()
        meshAgent?.tunnels?.toList()?.forEach { it.resetRemoteInput() }
    }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No accessibility content is recorded or transmitted. */ }
    override fun onInterrupt() { clearRemoteState() }
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        clearRemoteState()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        clearRemoteState()
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        unregisterReceiver(screenOff)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    private fun allowed(item: Pending): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && instance === this &&
            preferences.getBoolean("pref_remotecontrol", false) && item.source.canControlDesktop() &&
            meshAgent?.tunnels?.contains(item.source) == true && g_ScreenCaptureService?.inputGeometry === item.geometry &&
            !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked &&
            (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    private fun next() {
        if (dispatching) return
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (!allowed(item)) continue
            when (val action = item.action) {
                is DesktopAction.Gesture -> if (performGesture(action.points, action.duration)) return
                is DesktopAction.Scroll -> {
                    val distance = (item.geometry.height / 4).coerceAtLeast(1)
                    val end = DesktopPoint(action.point.x, (action.point.y + if (action.delta > 0) distance else -distance)
                        .coerceIn(0, item.geometry.height - 1))
                    if (end != action.point && performGesture(listOf(action.point, end), 300)) return
                }
                is DesktopAction.Navigate -> performGlobalAction(when (action.action) {
                    DesktopAction.Navigation.BACK -> GLOBAL_ACTION_BACK
                    DesktopAction.Navigation.HOME -> GLOBAL_ACTION_HOME
                    DesktopAction.Navigation.RECENTS -> GLOBAL_ACTION_RECENTS
                })
                is DesktopAction.Text -> editText(action.value, null)
                is DesktopAction.Key -> editText(null, action.code)
            }
        }
    }
    private fun performGesture(points: List<DesktopPoint>, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || points.isEmpty()) return false
        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            for (p in points.drop(1)) lineTo(p.x.toFloat(), p.y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(50, 1000))).build()
        dispatching = true
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { dispatching = false; next() }
            override fun onCancelled(gestureDescription: GestureDescription?) { dispatching = false; next() }
        }, handler)
        if (!accepted) dispatching = false
        return accepted
    }
    private fun editText(value: String?, key: Int?) {
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) { root.recycle(); return }
        try {
            if (!node.isEditable || !node.isEnabled || !node.isVisibleToUser || node.isPassword) return
            if (key == 9) {
                val target = node.focusSearch(android.view.View.FOCUS_FORWARD)
                try { target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } finally { target?.recycle() }
                return
            }
            val old = node.text?.toString() ?: ""
            if (old.length > 100000) return
            var start = node.textSelectionStart.takeIf { it in 0..old.length } ?: old.length
            var end = node.textSelectionEnd.takeIf { it in start..old.length } ?: start
            if (key == 37 || key == 39) {
                val position = if (key == 37) {
                    if (start != end) start else Character.offsetByCodePoints(old, start, if (start > 0) -1 else 0)
                } else {
                    if (start != end) end else Character.offsetByCodePoints(old, end, if (end < old.length) 1 else 0)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
                })
                return
            }
            if (start == end && key == 8 && start > 0) start = Character.offsetByCodePoints(old, start, -1)
            if (start == end && key == 46 && end < old.length) end = Character.offsetByCodePoints(old, end, 1)
            val inserted = value ?: if (key == 13) "\n" else ""
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        old.substring(0, start) + inserted + old.substring(end))
                })) {
                val cursor = start + inserted.length
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                })
            }
        } finally { if (node !== root) node.recycle(); root.recycle() }
    }
    companion object {
        @Volatile private var instance: RemoteControlService? = null
        fun isConnected(): Boolean = instance != null
        internal fun submit(source: MeshTunnel, action: DesktopAction, geometry: DesktopGeometry) {
            val service = instance ?: return
            service.handler.post {
                val item = Pending(source, action, geometry)
                if (service.allowed(item) && service.pending.size < 256) {
                    service.pending.addLast(item)
                    service.next()
                }
            }
        }
    }
}

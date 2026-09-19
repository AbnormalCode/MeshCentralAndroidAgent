package com.meshcentral.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// Helpers behind the extra agent console commands. Each one returns the text that is
// sent straight back to the MeshCentral console.

private const val MAX_LISTED_APPS = 300

/** List the applications that have a launcher entry. */
@Suppress("DEPRECATION")
internal fun getInstalledAppsText(context: Context): String {
    val packageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = try {
        packageManager.queryIntentActivities(intent, 0)
    } catch (ex: Exception) {
        return "Unable to read the application list"
    }

    val lines = resolved
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = try {
                info.loadLabel(packageManager).toString()
            } catch (ex: Exception) {
                packageName
            }
            Pair(label, packageName)
        }
        .distinctBy { it.second }
        .sortedBy { it.first.lowercase(Locale.getDefault()) }

    if (lines.isEmpty()) return "No applications found"
    val shown = lines.take(MAX_LISTED_APPS)
    val text = StringBuilder("${lines.size} application(s)\r\n")
    for ((label, packageName) in shown) { text.append("  $label ($packageName)\r\n") }
    if (shown.size < lines.size) { text.append("  ... ${lines.size - shown.size} more\r\n") }
    return text.toString().trimEnd()
}

/** Bring an installed application to the foreground. */
internal fun launchApplication(context: Context, packageName: String): String {
    val launchIntent = try {
        context.packageManager.getLaunchIntentForPackage(packageName)
    } catch (ex: Exception) {
        null
    } ?: return "No launchable application called \"$packageName\""
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(launchIntent)
        "Ok"
    } catch (ex: Exception) {
        "Unable to launch $packageName"
    }
}

/**
 * Read the clipboard. From Android 10 this only works while the agent is the
 * foreground application, which the caller is told about rather than guessing.
 */
internal fun getClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return "Clipboard unavailable"
    val clip = try {
        clipboard.primaryClip
    } catch (ex: Exception) {
        null
    }
    if ((clip == null) || (clip.itemCount == 0)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "Clipboard is empty, or not readable because the agent is not the foreground app"
        }
        return "Clipboard is empty"
    }
    val text = clip.getItemAt(0).coerceToText(context)?.toString()
    if (text.isNullOrEmpty()) return "Clipboard is empty"
    return text
}

/** Replace the clipboard content. */
internal fun setClipboardText(context: Context, text: String): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return "Clipboard unavailable"
    return try {
        clipboard.setPrimaryClip(ClipData.newPlainText("MeshCentral", text))
        "Ok"
    } catch (ex: Exception) {
        "Unable to set the clipboard"
    }
}

/** Report every audio stream level as a percentage. */
internal fun getVolumeText(context: Context): String {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return "Audio unavailable"
    val streams = listOf(
        Pair("media", AudioManager.STREAM_MUSIC),
        Pair("ring", AudioManager.STREAM_RING),
        Pair("notification", AudioManager.STREAM_NOTIFICATION),
        Pair("alarm", AudioManager.STREAM_ALARM),
        Pair("call", AudioManager.STREAM_VOICE_CALL)
    )
    val result = JSONObject()
    for ((name, stream) in streams) {
        try {
            val max = audio.getStreamMaxVolume(stream)
            val current = audio.getStreamVolume(stream)
            result.put(name, if (max > 0) (current * 100) / max else 0)
        } catch (ex: Exception) {
        }
    }
    result.put("ringermode", when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    })
    return result.toString(2)
}

/** Set the media volume, as a percentage of the maximum. */
internal fun setMediaVolume(context: Context, percent: Int): String {
    if ((percent < 0) || (percent > 100)) return "Value must be between 0 and 100"
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return "Audio unavailable"
    return try {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent) / 100, 0)
        "Ok"
    } catch (ex: Exception) {
        "Unable to set the volume"
    }
}

/** Report the most recent known location, without starting a new location fix. */
@SuppressLint("MissingPermission") // The permissions are checked at the top of this function
internal fun getLocationText(context: Context): String {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return "Location permission has not been granted to the agent"

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return "Location unavailable"

    var best: Location? = null
    try {
        for (provider in manager.getProviders(true)) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (ex: SecurityException) {
                null
            } ?: continue
            if ((best == null) || (location.time > best!!.time)) { best = location }
        }
    } catch (ex: Exception) {
        return "Unable to read the location"
    }

    val location = best ?: return "No recent location is available on this device"
    val result = JSONObject()
    result.put("latitude", location.latitude)
    result.put("longitude", location.longitude)
    result.put("accuracy", location.accuracy.toDouble())
    result.put("provider", location.provider)
    result.put("time", formatTimestamp(location.time))
    if (location.hasAltitude()) result.put("altitude", location.altitude)
    return result.toString(2)
}

/** Report how long the device has been running. */
internal fun getUptimeText(): String {
    val uptimeMs = SystemClock.elapsedRealtime()
    val result = JSONObject()
    result.put("uptime", formatDuration(uptimeMs))
    result.put("uptimeSeconds", uptimeMs / 1000)
    result.put("bootTime", formatTimestamp(System.currentTimeMillis() - uptimeMs))
    return result.toString(2)
}

/** Play the alarm sound so the device can be found. */
internal fun ringDevice(context: Context, durationMs: Long): String {
    if ((durationMs < 1) || (durationMs > 60000)) return "Value must be between 1 and 60000"
    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: return "No ringtone is available"
    val ringtone = try {
        RingtoneManager.getRingtone(context, uri)
    } catch (ex: Exception) {
        null
    } ?: return "No ringtone is available"

    return try {
        ringtone.play()
        thread {
            Thread.sleep(durationMs)
            try { ringtone.stop() } catch (ex: Exception) { }
        }
        "Ok"
    } catch (ex: Exception) {
        "Unable to play the ringtone"
    }
}

/** Report the agent version. */
@Suppress("DEPRECATION")
internal fun getVersionText(context: Context): String {
    val result = JSONObject()
    try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        result.put("version", info.versionName)
        result.put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong())
    } catch (ex: Exception) {
    }
    result.put("package", context.packageName)
    result.put("android", Build.VERSION.RELEASE)
    result.put("sdk", Build.VERSION.SDK_INT)
    return result.toString(2)
}

private fun formatTimestamp(millis: Long): String {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    } catch (ex: Exception) {
        millis.toString()
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    if (days > 0) return "${days}d ${hours}h ${minutes}m ${seconds}s"
    if (hours > 0) return "${hours}h ${minutes}m ${seconds}s"
    if (minutes > 0) return "${minutes}m ${seconds}s"
    return "${seconds}s"
}

package com.meshcentral.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image.Plane
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.util.Pair
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.*


class ScreenCaptureService : Service() {
    private var mMediaProjection: MediaProjection? = null
    private var mImageReader: ImageReader? = null
    private var mHandler: Handler? = null
    private var mHandlerThread: HandlerThread? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mProjectionCallback: MediaProjection.Callback? = null
    private var mDensity = 0
    private data class DisplayState(val width: Int, val height: Int, val density: Int, val rotation: Int)
    private var displayState: DisplayState? = null
    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }
    private var captureVisible = true
    private var forceFullFrame = true
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile var mWidth = 0
    @Volatile var mHeight = 0
    private val geometryState = DesktopFrameState()
    internal val frameGeometry: DesktopGeometry? get() = geometryState.geometry
    internal val inputGeometry: DesktopGeometry? get() = geometryState.inputGeometry

    // Tile data
    private var tilesWide : Int = 0
    private var tilesHigh : Int = 0
    private var tilesFullWide : Int = 0
    private var tilesFullHigh : Int = 0
    private var tilesRemainingWidth : Int = 0
    private var tilesRemainingHeight : Int = 0
    private var tilesCount : Int = 0
    private var oldcrcs : IntArray? = null
    private var newcrcs : IntArray? = null

    private inner class ImageAvailableListener : OnImageAvailableListener {

        override fun onImageAvailable(reader: ImageReader) {
            val frameToken = geometryState.beginFrame()
            if ((meshAgent == null) && (g_mainActivity != null)) {
                g_mainActivity!!.stopProjection()
                return
            }

            var bitmap: Bitmap? = null
            var image: android.media.Image? = null
            if (reader !== mImageReader) return

            try {
                image = reader.acquireLatestImage()
                // Skip this image if null or websocket push-back is high
                if ((image != null) && (checkDesktopTunnelPushback() < 65535) && (meshAgent?.tunnels?.getOrNull(0) != null)) {
                    if (image.width != mWidth || image.height != mHeight) return
                    val planes: Array<Plane> = image.getPlanes()
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * mWidth

                    // Create the bitmap
                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // Row padding is not part of the captured desktop or input mapping.
                    if (bitmap.width != mWidth) {
                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, mWidth, mHeight)
                        bitmap.recycle()
                        bitmap = cropped
                    }
                    val newWidth = scaledDimension(mWidth, g_desktop_scalingLevel)
                    val newHeight = scaledDimension(mHeight, g_desktop_scalingLevel)
                    if (newWidth != bitmap.width || newHeight != bitmap.height) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, false)
                        if (scaled !== bitmap) bitmap.recycle()
                        bitmap = scaled
                    }
                    val candidate = DesktopGeometry(mWidth, mHeight, bitmap.width, bitmap.height)
                    val oldGeometry = frameGeometry
                    val published = geometryState.publish(frameToken, candidate,
                        captureVisible && candidate.width == displayState?.width && candidate.height == displayState?.height)
                        ?: return
                    if (oldGeometry !== published) {
                        forceFullFrame = true
                        updateTunnelDisplaySize()
                    }

                    // Setup or update the CRC buffer and tile information.
                    val wt = (bitmap!!.width / 64)
                    val ht = (bitmap.height / 64)
                    if (frameWidth != bitmap.width || frameHeight != bitmap.height) {
                        frameWidth = bitmap.width
                        frameHeight = bitmap.height
                        forceFullFrame = true
                        tilesWide = wt;
                        tilesHigh = ht;
                        tilesFullWide = tilesWide
                        tilesFullHigh = tilesHigh
                        tilesRemainingWidth = (bitmap.width % 64);
                        tilesRemainingHeight = (bitmap.height % 64);
                        if (tilesRemainingWidth != 0) { tilesWide++; }
                        if (tilesRemainingHeight != 0) { tilesHigh++; }
                        tilesCount = (tilesWide * tilesHigh);
                        oldcrcs = IntArray(tilesCount); // 64 x 64 tiles
                        newcrcs = IntArray(tilesCount); // 64 x 64 tiles
                        //println("New tile count: $tilesCount")
                    }

                    // Compute all tile CRC's
                    computeAllCRCs(bitmap);

                    // Compute how many tiles have changed
                    var changedTiles : Int = 0;
                    for (i in 0 until tilesCount) { if (oldcrcs!![i] != newcrcs!![i]) { changedTiles++; } }
                    if (forceFullFrame || changedTiles > 0) {
                        // If 85% of the all tiles have changed, send the entire screen
                        if (forceFullFrame || (changedTiles * 100) >= (tilesCount * 85))
                        {
                            sendEntireImage(bitmap)
                            forceFullFrame = false
                            for (i in 0 until tilesCount) { oldcrcs!![i] = newcrcs!![i]; }
                        }
                        else
                        {
                            // Send all changed tiles
                            // This version has horizontal & vertical optimization, JPEG as wide as possible then as high as possible
                            var sendx : Int = -1;
                            var sendy : Int = 0;
                            var sendw : Int = 0;
                            for (i in 0 until tilesHigh)
                            {
                                for (j in 0 until tilesWide)
                                {
                                    val tileNumber : Int = (i * tilesWide) + j;
                                    if (oldcrcs!![tileNumber] != newcrcs!![tileNumber])
                                    {
                                        oldcrcs!![tileNumber] = newcrcs!![tileNumber];
                                        if (sendx == -1) { sendx = j; sendy = i; sendw = 1; } else { sendw += 1; }
                                    }
                                    else
                                    {
                                        if (sendx != -1) { sendSubBitmapRow(bitmap, sendx, sendy, sendw); sendx = -1; }
                                    }
                                }
                                if (sendx != -1) { sendSubBitmapRow(bitmap, sendx, sendy, sendw); sendx = -1; }
                            }
                            if (sendx != -1) { sendSubBitmapRow(bitmap, sendx, sendy, sendw); }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                bitmap?.recycle()
                image?.close()
            }
        }
    }

    private fun sendSubBitmapRow(bm: Bitmap, x : Int, y : Int, w : Int) {
        var h : Int = (y + 1)
        var exit : Boolean = false
        while (h < tilesHigh) {
            // Check if the row is all different
            for (xx in x until (x + w)) {
                val tileNumber = (h * tilesWide) + xx;
                if (oldcrcs!![tileNumber] == newcrcs!![tileNumber]) { exit = true; break; }
            }
            // If all different set the CRC's to the same, otherwise exit.
            if (!exit) {
                for (xx in x until (x + w)) {
                    val tileNumber : Int = (h * tilesWide) + xx;
                    oldcrcs!![tileNumber] = newcrcs!![tileNumber];
                }
            } else break;
            h++
        }
        h -= y
        sendSubImage(bm, x * 64, y * 64, w * 64, h * 64);
    }

    private fun Adler32(n : Int, state: Int) : Int {
        var a = state shr 16;
        var b = state and 0xFFFF;
        a = (a + n) % 65521
        b = (b + a) % 65521
        return (b shl 16) + a
    }

    // Compute all CRC's
    private fun computeAllCRCs(bm: Bitmap) {
        // Clear all CRC's
        for (i in 0 until tilesCount) { newcrcs!![i] = 1 }

        // Compute all of the CRC's
        for (y in 0 until tilesHigh) {
            var h : Int = 64;
            if (((y * 64) + 64) > bm.height) { h = (bm.height - (y * 64)) }
            for (x in 0 until tilesWide) {
                var w : Int = 64;
                if (((x * 64) + 64) > bm.width) { w = (bm.width - (x * 64)) }
                val t = (y * tilesWide) + x
                val pixels = IntArray(w * h)
                bm.getPixels(pixels, 0, w, x * 64, y * 64, w, h)
                for (i in 0 until pixels.size) { newcrcs!![t] = Adler32(pixels[i], newcrcs!![t]) }
            }
        }
    }

    // Send a sub bitmap
    private fun sendSubImage(bm: Bitmap, x: Int, y: Int, w: Int, h :Int) {
        var ww = w;
        var hh = h;
        if (x + w > bm.width) { ww = (bm.width - x) }
        if (y + h > bm.height) { hh = (bm.height - y) }
        // Extract the sub bitmap if needed
        val cropedBitmap: Bitmap = Bitmap.createBitmap(bm, x, y, ww, hh)
        // Write bitmap to a memory and build a jumbo command
        var bytesOut = ByteArrayOutputStream()
        var dos = DataOutputStream(bytesOut)
        dos.writeShort(27) // Jumbo command
        dos.writeShort(8) // Jumbo command size
        dos.writeInt(0) // Next command size (0 for now)
        dos.writeShort(3) // Image command
        dos.writeShort(0) // Image command size, 0 since jumbo is used
        dos.writeShort(x) // X
        dos.writeShort(y) // Y
        if (g_desktop_imageType == 4) { // WebP
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                cropedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, g_desktop_compressionLevel, dos)
            } else {
                @Suppress("DEPRECATION")
                cropedBitmap.compress(Bitmap.CompressFormat.WEBP, g_desktop_compressionLevel, dos)
            }
        } else if (g_desktop_imageType == 2) { // PNG
            cropedBitmap.compress(Bitmap.CompressFormat.PNG, g_desktop_compressionLevel, dos)
        } else { // JPEG (Default)
            cropedBitmap.compress(Bitmap.CompressFormat.JPEG, g_desktop_compressionLevel, dos)
        }
        cropedBitmap.recycle()
        var data = bytesOut.toByteArray()
        var cmdSize : Int = (data.size - 8)
        data[4] = (cmdSize shr 24).toByte()
        data[5] = (cmdSize shr 16).toByte()
        data[6] = (cmdSize shr 8).toByte()
        data[7] = (cmdSize).toByte()
        sendDesktopTunnelData(data.toByteString()) // Send the data to all remote desktop tunnels
    }

    private fun sendEntireImage(bm: Bitmap) {
        // Write bitmap to a memory and build a jumbo command
        var bytesOut = ByteArrayOutputStream()
        var dos = DataOutputStream(bytesOut)
        dos.writeShort(27) // Jumbo command
        dos.writeShort(8) // Jumbo command size
        dos.writeInt(0) // Next command size (0 for now)
        dos.writeShort(3) // Image command
        dos.writeShort(0) // Image command size, 0 since jumbo is used
        dos.writeShort(0) // X
        dos.writeShort(0) // Y
        if (g_desktop_imageType == 4) { // WebP
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                bm.compress(Bitmap.CompressFormat.WEBP_LOSSY, g_desktop_compressionLevel, dos)
            } else {
                @Suppress("DEPRECATION")
                bm.compress(Bitmap.CompressFormat.WEBP, g_desktop_compressionLevel, dos)
            }
        } else if (g_desktop_imageType == 2) { // PNG
            bm.compress(Bitmap.CompressFormat.PNG, g_desktop_compressionLevel, dos)
        } else { // JPEG (Default)
            bm.compress(Bitmap.CompressFormat.JPEG, g_desktop_compressionLevel, dos)
        }
        var data = bytesOut.toByteArray()
        var cmdSize : Int = (data.size - 8)
        data[4] = (cmdSize shr 24).toByte()
        data[5] = (cmdSize shr 16).toByte()
        data[6] = (cmdSize shr 8).toByte()
        data[7] = (cmdSize).toByte()
        sendDesktopTunnelData(data.toByteString()) // Send the data to all remote desktop tunnels
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) updateCaptureDisplay() }
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) stopProjection() }
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) updateCaptureDisplay() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mHandler?.post { updateCaptureDisplay() }
    }
    @Suppress("DEPRECATION")
    private fun readDisplayState(): DisplayState {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("Default display is unavailable")
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
        } else null
        return DisplayState(bounds?.width() ?: metrics.widthPixels,
            bounds?.height() ?: metrics.heightPixels, metrics.densityDpi, display.rotation)
    }
    private fun updateCaptureDisplay() {
        if (mMediaProjection == null) return
        try {
            val current = readDisplayState()
            if (current != displayState) {
                displayState = current
                invalidateGeometry()
                resizeCapture(current.width, current.height)
            }
        } catch (ex: Exception) {
            sendAgentConsole("Display changed; screen sharing stopped. Please share again.")
            stopProjection()
        }
    }
    private fun invalidateGeometry() { geometryState.invalidate(); forceFullFrame = true }
    internal fun requestRefresh() {
        // Invalidate immediately: a frame already being encoded cannot restore old coordinates.
        geometryState.invalidate()
        mHandler?.post {
            try { resizeCapture(mWidth, mHeight, force = true) }
            catch (ex: Exception) { stopProjection() }
        }
    }
    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            geometryState.invalidate()
            if (g_ScreenCaptureService === this@ScreenCaptureService) g_ScreenCaptureService = null
            stopSelf()
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && mVirtualDisplay != null) {
                try { resizeCapture(width, height) }
                catch (ex: Exception) { stopProjection() }
            }
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            captureVisible = isVisible
            invalidateGeometry()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        mHandlerThread = HandlerThread("ScreenCaptureService")
        mHandlerThread!!.start()
        mHandler = Handler(mHandlerThread!!.looper)
    }

    override fun onDestroy() {
        geometryState.invalidate()
        if (g_ScreenCaptureService === this) g_ScreenCaptureService = null
        val handler = mHandler
        val thread = mHandlerThread
        // Serialize disposal with frame processing to avoid closing a reader mid-frame.
        handler?.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            displayManager.unregisterDisplayListener(displayListener)
            mProjectionCallback?.let { mMediaProjection?.unregisterCallback(it) }
            mProjectionCallback = null
            releaseDisplayResources()
            val projection = mMediaProjection
            mMediaProjection = null
            projection?.stop()
            thread?.quitSafely()
        }
        if (handler == null) cleanup.run() else handler.post(cleanup)
        mHandler = null
        mHandlerThread = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ScreenCaptureService.Companion.isStartCommand(intent)) {
            // Create notification
            val notification: Pair<Int, Notification> = NotificationUtils.getNotification(this)
            startForeground(notification.first!!, notification.second)
            // Start projection
            val resultCode = intent.getIntExtra(ScreenCaptureService.Companion.RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(ScreenCaptureService.Companion.DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(ScreenCaptureService.Companion.DATA)
            }
            mHandler?.post { startProjection(resultCode, data) }
        } else if (intent != null && ScreenCaptureService.Companion.isStopCommand(intent)) {
            stopProjection()
            stopSelf()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent?) {
        if (mMediaProjection != null) return
        try {
            require(resultCode == Activity.RESULT_OK && data != null)
            mMediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(resultCode, data)
            displayState = readDisplayState()
            mProjectionCallback = MediaProjectionStopCallback()
            mMediaProjection!!.registerCallback(mProjectionCallback!!, mHandler)
            createVirtualDisplay()
            displayManager.registerDisplayListener(displayListener, mHandler)
            g_ScreenCaptureService = this
            sendAgentConsole("Started display sharing")
        } catch (ex: Exception) {
            sendAgentConsole("Unable to start display sharing. Please grant screen sharing again.")
            stopSelf()
        }
    }

    private fun sendAgentConsole(r: String) {
        if (meshAgent != null) {
            meshAgent!!.sendConsoleResponse(r, sessionid = null)
        }
    }

    private fun stopProjection() {
        geometryState.invalidate()
        if (g_ScreenCaptureService === this) g_ScreenCaptureService = null
        mHandler?.post {
            mMediaProjection?.stop()
            sendAgentConsole("Stopped display sharing")
            stopSelf()
        }
    }

    private fun releaseDisplayResources() {
        mImageReader?.setOnImageAvailableListener(null, null)
        mImageReader?.close()
        mImageReader = null
        mVirtualDisplay?.release()
        mVirtualDisplay = null
    }

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        val state = displayState ?: return
        mWidth = state.width; mHeight = state.height; mDensity = state.density
        invalidateGeometry()
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
        mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
        // Android 14+ permits one createVirtualDisplay call per projection token.
        mVirtualDisplay = mMediaProjection!!.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight,
            mDensity, virtualDisplayFlags, mImageReader!!.surface, null, mHandler)
    }
    private fun resizeCapture(width: Int, height: Int, force: Boolean = false) {
        val display = mVirtualDisplay ?: return
        val density = displayState?.density ?: mDensity
        if (!force && width == mWidth && height == mHeight && density == mDensity) return
        invalidateGeometry()
        val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val old = mImageReader
        try {
            display.surface = null
            display.resize(width, height, density)
            display.surface = replacement.surface
            mImageReader = replacement
            mWidth = width; mHeight = height; mDensity = density
            replacement.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
            old?.setOnImageAvailableListener(null, null)
            old?.close()
            sendAgentConsole("Screen resized: $width x $height")
        } catch (ex: Exception) {
            replacement.close()
            throw ex
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val RESULT_CODE = "RESULT_CODE"
        private const val DATA = "DATA"
        private const val ACTION = "ACTION"
        private const val START = "START"
        private const val STOP = "STOP"
        private const val SCREENCAP_NAME = "screencap"
        fun getStartIntent(context: Context?, resultCode: Int, data: Intent?): Intent {
            val intent = Intent(context, ScreenCaptureService::class.java)
            intent.putExtra(ScreenCaptureService.Companion.ACTION, ScreenCaptureService.Companion.START)
            intent.putExtra(ScreenCaptureService.Companion.RESULT_CODE, resultCode)
            intent.putExtra(ScreenCaptureService.Companion.DATA, data)
            return intent
        }

        fun getStopIntent(context: Context?): Intent {
            val intent = Intent(context, ScreenCaptureService::class.java)
            intent.putExtra(ScreenCaptureService.Companion.ACTION, ScreenCaptureService.Companion.STOP)
            return intent
        }

        private fun isStartCommand(intent: Intent): Boolean {
            return (intent.hasExtra(ScreenCaptureService.Companion.RESULT_CODE) && intent.hasExtra(ScreenCaptureService.Companion.DATA)
                    && intent.hasExtra(ScreenCaptureService.Companion.ACTION) && intent.getStringExtra(ScreenCaptureService.Companion.ACTION) == ScreenCaptureService.Companion.START)
        }

        private fun isStopCommand(intent: Intent): Boolean {
            return intent.hasExtra(ScreenCaptureService.Companion.ACTION) && intent.getStringExtra(ScreenCaptureService.Companion.ACTION) == ScreenCaptureService.Companion.STOP
        }

        private val virtualDisplayFlags: Int
        get() = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    }

    fun updateTunnelDisplaySize() {
        if (meshAgent == null) return;
        for (t in meshAgent!!.tunnels) {
            if ((t.state == 2) && (t.usage == 2)) { // If this is a connected desktop tunnel...
                t.updateDesktopDisplaySize() // Send updated screen size
            }
        }
    }

    fun checkNoMoreDesktopTunnels() {
        if (meshAgent == null) return;
        var desktopTunnelCloud = 0
        for (t in meshAgent!!.tunnels) {
            // If this is a connected desktop tunnel, count it
            if ((t.state == 2) && (t.usage == 2)) { desktopTunnelCloud++ }
        }
        if (desktopTunnelCloud == 0) {
            // If there are no more desktop tunnels, stop projection
            if (!g_autoConsent) {
                g_mainActivity!!.stopProjection()
            } else { // reset the tilesFullWide and tilesFullHigh so on next connect it will send the whole image rather than changed tiles
                requestRefresh()
            }
        }
    }

    // Get the maximum outbound queue size of all remote desktop sockets
    fun checkDesktopTunnelPushback() : Long {
        if (meshAgent == null) return -1;
        var maxQueueSize : Long = 0
        for (t in meshAgent!!.tunnels) {
            // If this is a connected desktop tunnel, count it
            if ((t.state == 2) && (t.usage == 2) && (t._webSocket != null)) {
                var qs : Long? = t._webSocket?.queueSize()
                if ((qs != null) && (qs > maxQueueSize)) { maxQueueSize = qs }
            }
        }
        return maxQueueSize
    }

    // Send data to all remote desktop sockets
    fun sendDesktopTunnelData(data: ByteString) {
        if (meshAgent == null) return;
        for (t in meshAgent!!.tunnels) {
            // If this is a connected desktop tunnel, send the data
            if ((t.state == 2) && (t.usage == 2) && (t._webSocket != null)) {
                t._webSocket!!.send(data)
            }
        }
    }
}

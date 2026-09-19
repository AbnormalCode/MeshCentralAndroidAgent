package com.meshcentral.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputProtocolTest {

    private fun message(vararg values: Int): ByteArray {
        return ByteArray(values.size) { values[it].toByte() }
    }

    private fun mouse(buttons: Int, x: Int, y: Int, wheel: Int? = null): ByteArray {
        val size = if (wheel == null) 10 else 12
        val bytes = arrayListOf(
            0, MNG_KVM_MOUSE, 0, size,
            buttons shr 8, buttons and 0xFF,
            x shr 8, x and 0xFF,
            y shr 8, y and 0xFF
        )
        if (wheel != null) {
            bytes.add((wheel shr 8) and 0xFF)
            bytes.add(wheel and 0xFF)
        }
        return message(*bytes.toIntArray())
    }

    @Test
    fun decodesMouseMessages() {
        val event = decodeRemoteInputCommand(MNG_KVM_MOUSE, mouse(MOUSE_LEFT_DOWN, 300, 700))
        assertEquals(RemoteInputEvent.Mouse(MOUSE_LEFT_DOWN, 300, 700, 0), event)
    }

    @Test
    fun decodesMouseWheel() {
        val event = decodeRemoteInputCommand(MNG_KVM_MOUSE, mouse(MOUSE_WHEEL, 10, 20, -120))
        assertEquals(RemoteInputEvent.Mouse(MOUSE_WHEEL, 10, 20, -120), event)
    }

    @Test
    fun decodesKeyAndUnicodeMessages() {
        assertEquals(
            RemoteInputEvent.Key(KEY_ACTION_DOWN, KEYCODE_ENTER),
            decodeRemoteInputCommand(MNG_KVM_KEY, message(0, MNG_KVM_KEY, 0, 6, KEY_ACTION_DOWN, KEYCODE_ENTER))
        )
        assertEquals(
            RemoteInputEvent.UnicodeKey(KEY_ACTION_PRESS, 0x41),
            decodeRemoteInputCommand(MNG_KVM_KEY_UNICODE, message(0, MNG_KVM_KEY_UNICODE, 0, 6, 0x00, 0x41))
        )
        assertEquals(
            RemoteInputEvent.InputLock(true),
            decodeRemoteInputCommand(MNG_KVM_INPUT_LOCK, message(0, MNG_KVM_INPUT_LOCK, 0, 5, 1))
        )
    }

    @Test
    fun rejectsTruncatedOrUnknownMessages() {
        assertNull(decodeRemoteInputCommand(MNG_KVM_MOUSE, message(0, MNG_KVM_MOUSE, 0, 10, 0, 2)))
        assertNull(decodeRemoteInputCommand(MNG_KVM_KEY, message(0, MNG_KVM_KEY, 0, 6, 0)))
        assertNull(decodeRemoteInputCommand(5, message(0, 5, 0, 6, 1, 40)))
    }

    @Test
    fun pressAndReleaseInPlaceIsATap() {
        val recognizer = MouseGestureRecognizer()
        assertNull(recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_DOWN, 100, 200, 0), 1000))
        val gesture = recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_UP, 102, 201, 0), 1100)
        assertEquals(RemoteInputGesture.Tap(100, 200), gesture)
    }

    @Test
    fun holdingStillIsALongPress() {
        val recognizer = MouseGestureRecognizer()
        recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_DOWN, 100, 200, 0), 1000)
        val gesture = recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_UP, 100, 200, 0), 1800)
        assertEquals(RemoteInputGesture.LongPress(100, 200), gesture)
    }

    @Test
    fun draggingIsASwipeOverTheSameTime() {
        val recognizer = MouseGestureRecognizer()
        recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_DOWN, 100, 900, 0), 1000)
        recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_MOVE, 100, 600, 0), 1150)
        val gesture = recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_UP, 100, 300, 0), 1300)
        assertEquals(RemoteInputGesture.Swipe(100, 900, 100, 300, 300), gesture)
    }

    @Test
    fun releaseWithoutCoordinatesUsesTheLastMovePoint() {
        val recognizer = MouseGestureRecognizer()
        recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_DOWN, 100, 900, 0), 1000)
        recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_MOVE, 140, 400, 0), 1200)
        val gesture = recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_UP, 0, 0, 0), 1250)
        assertEquals(RemoteInputGesture.Swipe(100, 900, 140, 400, 250), gesture)
    }

    @Test
    fun releaseWithoutAPressIsIgnored() {
        val recognizer = MouseGestureRecognizer()
        assertNull(recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_LEFT_UP, 100, 200, 0), 1000))
    }

    @Test
    fun wheelAndSecondaryButtonsMapToActions() {
        val recognizer = MouseGestureRecognizer()
        assertEquals(
            RemoteInputGesture.Scroll(50, 60, 120),
            recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_WHEEL, 50, 60, 120), 1000)
        )
        assertEquals(
            RemoteInputGesture.Global(RemoteGlobalAction.BACK),
            recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_RIGHT_UP, 50, 60, 0), 1100)
        )
        assertEquals(
            RemoteInputGesture.Global(RemoteGlobalAction.HOME),
            recognizer.onMouse(RemoteInputEvent.Mouse(MOUSE_MIDDLE_UP, 50, 60, 0), 1200)
        )
    }

    @Test
    fun scalesCoordinatesBackToDevicePixels() {
        assertEquals(400, descaleDesktopCoordinate(400, 1024))
        assertEquals(800, descaleDesktopCoordinate(400, 512))
        assertEquals(400, descaleDesktopCoordinate(400, 0))
    }

    @Test
    fun mapsNavigationKeys() {
        assertEquals(RemoteGlobalAction.BACK, globalActionForKeyCode(KEYCODE_ESCAPE))
        assertEquals(RemoteGlobalAction.HOME, globalActionForKeyCode(KEYCODE_HOME))
        assertNull(globalActionForKeyCode(KEYCODE_ENTER))
    }

    @Test
    fun recognisesStoragePaths() {
        assertTrue(StorageAccess.isStoragePath(StorageAccess.ROOT_NAME))
        assertTrue(StorageAccess.isStoragePath("${StorageAccess.ROOT_NAME}/Reports"))
        assertFalse(StorageAccess.isStoragePath("Sdcard/Files"))
        assertFalse(StorageAccess.isStoragePath("FilesOther"))
    }
}

package com.meshcentral.agent

import org.junit.Assert.*
import org.junit.Test

class DesktopInputTest {
    private val cover = DesktopGeometry(1080, 2520, 540, 1260)
    private fun packet(vararg data: Int) = desktopPackets(data.map { it.toByte() }.toByteArray())!!.single()
    private fun key(code: Int, up: Boolean = false) = packet(0, 1, 0, 6, if (up) 1 else 0, code)
    private fun mouse(flags: Int, x: Int = 300, y: Int = 400) = packet(0, 2, 0, 10, 0, flags, x shr 8, x and 255, y shr 8, y and 255)

    @Test fun lateFramesCannotRestoreCoordinatesAfterResizeOrStop() {
        val state = DesktopFrameState()
        val token = state.beginFrame()
        state.invalidate()
        assertNull(state.publish(token, cover, true))
        assertNull(state.inputGeometry)
        val fresh = state.publish(state.beginFrame(), cover, true)
        assertSame(fresh, state.inputGeometry)
        state.invalidate()
        assertNull(state.inputGeometry)
        assertNull(state.publish(token, cover, true))
    }
    @Test fun geometryIdentityChangesAcrossSessionsButNotIdenticalFrames() {
        val state = DesktopFrameState()
        val first = state.publish(state.beginFrame(), cover, true)
        assertSame(first, state.publish(state.beginFrame(), cover.copy(), true))
        state.publish(state.beginFrame(), cover, false)
        assertNull(state.inputGeometry)
        state.invalidate()
        assertNotSame(first, state.publish(state.beginFrame(), cover.copy(), true))
    }
    @Test fun validatesWholePacketMessagesAndUnsignedFields() {
        assertNull(desktopPackets(byteArrayOf(0, 2)))
        assertNull(desktopPackets(byteArrayOf(0, 2, 0, 0)))
        assertNull(desktopPackets(byteArrayOf(0, 2, 0, 10, 0)))
        assertNull(desktopPackets(key(65).bytes + byteArrayOf(1)))
        assertEquals(65535, unsignedShort(byteArrayOf(-1, -1), 0))
        assertEquals(2, desktopPackets(key(27).bytes + key(27, true).bytes)!!.size)
    }
    @Test fun mapsCoverInnerAndLandscapeWithoutOutOfBoundsTaps() {
        assertEquals(DesktopPoint(600, 800), cover.map(300, 400))
        assertEquals(DesktopPoint(1078, 2518), cover.map(539, 1259))
        assertNull(cover.map(540, 0)); assertNull(cover.map(-1, 0))
        assertEquals(DesktopPoint(1000, 900), DesktopGeometry(2184, 1968, 1092, 984).map(500, 450))
        assertEquals(DesktopPoint(1600, 1000), DesktopGeometry(2520, 1080, 1260, 540).map(800, 500))
        assertEquals(1, scaledDimension(1, 128))
        assertEquals(540, scaledDimension(1080, 512))
        assertEquals(1080, scaledDimension(1080, 65535))
    }
    @Test fun emitsDragOnlyAfterMatchingLeftRelease() {
        val d = DesktopInputDecoder()
        assertNull(d.decode(mouse(4), cover, 0))
        assertNull(d.decode(mouse(2), cover, 10))
        assertNull(d.decode(mouse(0, 400, 500), cover, 50))
        val drag = d.decode(mouse(4, 450, 600), cover, 110) as DesktopAction.Gesture
        assertEquals(listOf(DesktopPoint(600, 800), DesktopPoint(800, 1000), DesktopPoint(900, 1200)), drag.points)
        assertEquals(100L, drag.duration)
        assertNull(d.decode(mouse(4), cover, 120))
    }
    @Test fun resetDropsGesturesAndModifiersAfterConsentLoss() {
        val d = DesktopInputDecoder()
        d.decode(mouse(2), cover, 0); d.decode(key(17), cover, 0); d.reset()
        assertNull(d.decode(mouse(4), cover, 100))
        assertEquals(DesktopAction.Text("a"), d.decode(key(65), cover, 0))
    }
    @Test fun rejectsOutOfBoundsAndStaleDrags() {
        val d = DesktopInputDecoder()
        d.decode(mouse(2), cover, 0)
        assertNull(d.decode(mouse(4, 600, 10), cover, 100))
        assertNull(d.decode(mouse(4), cover, 110))
        d.decode(mouse(2), cover, 0)
        assertNull(d.decode(mouse(4), cover, 10001))
    }
    @Test fun navigationAndSignedWheelUseExistingProtocol() {
        val d = DesktopInputDecoder()
        assertEquals(DesktopAction.Navigate(DesktopAction.Navigation.BACK), d.decode(mouse(16), cover, 0))
        assertEquals(DesktopAction.Navigate(DesktopAction.Navigation.HOME), d.decode(mouse(64), cover, 0))
        val scroll = d.decode(packet(0, 2, 0, 12, 0, 0, 0, 100, 0, 100, 255, 135), cover, 0) as DesktopAction.Scroll
        assertEquals(-121, scroll.delta)
        assertEquals(DesktopPoint(200, 200), scroll.point)
    }
    @Test fun typingHonorsModifiersAndIgnoresKeyUpAndShortcuts() {
        val d = DesktopInputDecoder()
        assertEquals(DesktopAction.Text("a"), d.decode(key(65), cover, 0))
        assertNull(d.decode(key(65, true), cover, 0))
        d.decode(key(16), cover, 0)
        assertEquals(DesktopAction.Text("A"), d.decode(key(65), cover, 0))
        d.decode(key(16, true), cover, 0); d.decode(key(17), cover, 0)
        assertNull(d.decode(key(67), cover, 0))
        d.decode(key(17, true), cover, 0)
        assertEquals(DesktopAction.Key(8), d.decode(key(8), cover, 0))
        assertEquals(DesktopAction.Navigate(DesktopAction.Navigation.BACK), d.decode(key(27), cover, 0))
    }
    @Test fun combinesUnicodeSurrogatesWithoutTypingOnKeyUp() {
        val d = DesktopInputDecoder()
        assertNull(d.decode(packet(0, 85, 0, 7, 0, 0xD8, 0x3D), cover, 0))
        assertNull(d.decode(packet(0, 85, 0, 7, 1, 0xD8, 0x3D), cover, 0))
        assertEquals(DesktopAction.Text("😀"), d.decode(packet(0, 85, 0, 7, 0, 0xDE, 0), cover, 0))
        assertNull(d.decode(packet(0, 85, 0, 7, 0, 0xDE, 0), cover, 0))
        assertEquals(DesktopAction.Text("中"), d.decode(packet(0, 85, 0, 7, 0, 0x4E, 0x2D), cover, 0))
    }
    @Test fun deniesMissingViewOnlyAndRestrictedRights() {
        assertTrue(desktopControlAllowed(0xFFFFFFFFL, false))
        assertTrue(desktopControlAllowed(8, false))
        assertFalse(desktopControlAllowed(0, false))
        assertFalse(desktopControlAllowed(8L or 256L, false))
        assertFalse(desktopControlAllowed(8L or 4096L, false))
        assertFalse(desktopControlAllowed(8L or 65536L, false))
        assertFalse(desktopControlAllowed(0xFFFFFFFFL, true))
    }
}

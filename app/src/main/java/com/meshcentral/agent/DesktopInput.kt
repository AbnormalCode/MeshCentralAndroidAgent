package com.meshcentral.agent

/** Advertised frame dimensions exclude ImageReader row padding. */
internal data class DesktopGeometry(val width: Int, val height: Int, val streamWidth: Int, val streamHeight: Int) {
    init { require(width > 0 && height > 0 && streamWidth > 0 && streamHeight > 0) }
    fun map(x: Int, y: Int): DesktopPoint? {
        if (x !in 0 until streamWidth || y !in 0 until streamHeight) return null
        return DesktopPoint((x.toLong() * width / streamWidth).toInt().coerceIn(0, width - 1),
            (y.toLong() * height / streamHeight).toInt().coerceIn(0, height - 1))
    }
}
internal data class DesktopPoint(val x: Int, val y: Int)
internal data class DesktopPacket(val command: Int, val bytes: ByteArray)

/** A late frame must not restore input coordinates after resize or projection stop. */
internal class DesktopFrameState {
    private var generation = 0L
    @Volatile var geometry: DesktopGeometry? = null
        private set
    @Volatile var inputGeometry: DesktopGeometry? = null
        private set
    @Synchronized fun beginFrame(): Long = generation
    @Synchronized fun invalidate() { generation++; geometry = null; inputGeometry = null }
    @Synchronized fun publish(token: Long, candidate: DesktopGeometry, allowInput: Boolean): DesktopGeometry? {
        if (token != generation) return null
        val current = geometry?.takeIf { it == candidate } ?: candidate
        geometry = current
        inputGeometry = current.takeIf { allowInput }
        return current
    }
}

internal fun unsignedShort(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

/** Validate the whole message before applying even the first concatenated packet. */
internal fun desktopPackets(bytes: ByteArray): List<DesktopPacket>? {
    val result = mutableListOf<DesktopPacket>()
    var offset = 0
    while (offset < bytes.size) {
        if (bytes.size - offset < 4) return null
        val size = unsignedShort(bytes, offset + 2)
        if (size < 4 || size > bytes.size - offset) return null
        result.add(DesktopPacket(unsignedShort(bytes, offset), bytes.copyOfRange(offset, offset + size)))
        offset += size
    }
    return result
}
internal fun desktopControlAllowed(rights: Long, viewOnly: Boolean): Boolean =
    !viewOnly && (rights == 0xFFFFFFFFL || ((rights and 8L) != 0L && (rights and (256L or 4096L or 65536L)) == 0L))
internal fun scaledDimension(size: Int, scale: Int): Int =
    (size.toLong() * scale.coerceIn(128, 1024) / 1024).toInt().coerceAtLeast(1)

internal sealed class DesktopAction {
    data class Gesture(val points: List<DesktopPoint>, val duration: Long) : DesktopAction()
    data class Scroll(val point: DesktopPoint, val delta: Int) : DesktopAction()
    data class Text(val value: String) : DesktopAction()
    data class Key(val code: Int) : DesktopAction()
    enum class Navigation { BACK, HOME, RECENTS }
    data class Navigate(val action: Navigation) : DesktopAction()
}

/** Input state belongs to a single tunnel and coordinate generation. */
internal class DesktopInputDecoder {
    private val points = mutableListOf<DesktopPoint>()
    private var started = 0L
    private var shift = false
    private var control = false
    private var alt = false
    private var meta = false
    private var caps = false
    private var highSurrogate: Char? = null
    @Synchronized fun reset() {
        points.clear(); shift = false; control = false; alt = false; meta = false; caps = false
        highSurrogate = null
    }
    @Synchronized fun decode(packet: DesktopPacket, geometry: DesktopGeometry, now: Long): DesktopAction? {
        val b = packet.bytes
        return when (packet.command) {
            2 -> {
                if (b.size != 10 && b.size != 12) return null
                val flags = unsignedShort(b, 4)
                val point = geometry.map(unsignedShort(b, 6), unsignedShort(b, 8))
                if (point == null) { points.clear(); return null }
                if (b.size == 12) {
                    points.clear()
                    val delta = unsignedShort(b, 10).toShort().toInt()
                    if (flags == 0 && delta != 0) DesktopAction.Scroll(point, delta) else null
                } else when (flags) {
                    2 -> { points.clear(); points.add(point); started = now; null }
                    0 -> { if (points.isNotEmpty() && points.size < 128 && points.last() != point) points.add(point); null }
                    4 -> {
                        if (points.isEmpty()) return null
                        if (now - started > 10000) { points.clear(); return null }
                        points.add(point)
                        val result = DesktopAction.Gesture(points.toList(), (now - started).coerceIn(50, 5000))
                        points.clear(); result
                    }
                    16 -> { points.clear(); DesktopAction.Navigate(DesktopAction.Navigation.BACK) }
                    64 -> { points.clear(); DesktopAction.Navigate(DesktopAction.Navigation.HOME) }
                    else -> null
                }
            }
            1 -> {
                if (b.size != 6) return null
                val flag = b[4].toInt() and 255
                if (flag !in listOf(0, 1, 3, 4)) return null
                val down = flag == 0 || flag == 4
                val code = b[5].toInt() and 255
                when (code) {
                    16, 160, 161 -> { shift = down; null }
                    17, 162, 163 -> { control = down; null }
                    18, 164, 165 -> { alt = down; null }
                    91, 92 -> { meta = down; null }
                    20 -> { if (down) caps = !caps; null }
                    else -> {
                        if (!down || control || alt || meta) return null
                        when (code) {
                            27, 166 -> DesktopAction.Navigate(DesktopAction.Navigation.BACK)
                            36 -> DesktopAction.Navigate(DesktopAction.Navigation.HOME)
                            93 -> DesktopAction.Navigate(DesktopAction.Navigation.RECENTS)
                            8, 9, 13, 37, 39, 46 -> DesktopAction.Key(code)
                            else -> printableKey(code)?.let { DesktopAction.Text(it) }
                        }
                    }
                }
            }
            85 -> {
                if (b.size != 7 || b[4].toInt() != 0 || control || alt || meta) return null
                val c = unsignedShort(b, 5).toChar()
                if (Character.isHighSurrogate(c)) { highSurrogate = c; return null }
                val previous = highSurrogate
                highSurrogate = null
                when {
                    Character.isLowSurrogate(c) -> previous?.let { DesktopAction.Text("$it$c") }
                    !c.isISOControl() -> DesktopAction.Text(c.toString())
                    else -> null
                }
            }
            else -> null
        }
    }
    private fun printableKey(code: Int): String? = when (code) {
        in 65..90 -> (if (shift.xor(caps)) code.toChar() else (code + 32).toChar()).toString()
        in 48..57 -> (if (shift) ")!@#$%^&*("[code - 48] else code.toChar()).toString()
        in 96..105 -> ('0' + (code - 96)).toString()
        32 -> " "
        186 -> if (shift) ":" else ";"
        187 -> if (shift) "+" else "="
        188 -> if (shift) "<" else ","
        189 -> if (shift) "_" else "-"
        190 -> if (shift) ">" else "."
        191 -> if (shift) "?" else "/"
        192 -> if (shift) "~" else "`"
        219 -> if (shift) "{" else "["
        220 -> if (shift) "|" else "\\"
        221 -> if (shift) "}" else "]"
        222 -> if (shift) "\"" else "'"
        else -> null
    }
}

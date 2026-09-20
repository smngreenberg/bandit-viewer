package com.smngreenberg.banditviewer.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reassembles JPEG frames from UDP datagrams according to the Bandit protocol.
 */
class FrameAssembler {
    data class Frame(val ptsSeconds: Float, val jpeg: ByteArray)

    private var waiting = true
    private var expectedLen = 0
    private var buffer = ByteArray(0)
    private var bufferPos = 0
    private var nextPacket = 0
    private var pts = 0.0f

    private val _droppedFrames = AtomicInteger(0)
    val droppedFrames: Int get() = _droppedFrames.get()

    private val _badPackets = AtomicInteger(0)
    val badPackets: Int get() = _badPackets.get()

    fun reset() {
        waiting = true
        expectedLen = 0
        bufferPos = 0
        nextPacket = 0
        pts = 0.0f
        _droppedFrames.set(0)
        _badPackets.set(0)
    }

    fun feed(packet: ByteArray, length: Int): Frame? {
        if (length < 7) {
            _badPackets.incrementAndGet()
            return null
        }

        val bb = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.BIG_ENDIAN)
        val sync = bb.short.toInt() and 0xFFFF
        val msg = bb.get().toInt() and 0xFF
        val pnum = bb.short.toInt() and 0xFFFF
        val plen = bb.short.toInt() and 0xFFFF

        if (sync != 0x55AA || length != plen + 7 || (msg != 0 && msg != 1)) {
            _badPackets.incrementAndGet()
            return null
        }

        if (msg == 0) { // START
            if (plen < 8) {
                _badPackets.incrementAndGet()
                return null
            }
            val imgLen = bb.int.toLong() and 0xFFFFFFFFL
            pts = bb.float

            // Guard against absurd image length
            if (imgLen > 2_000_000 || imgLen <= 0) {
                _badPackets.incrementAndGet()
                waiting = true
                return null
            }

            expectedLen = imgLen.toInt()
            if (buffer.size < expectedLen) {
                buffer = ByteArray(expectedLen)
            }
            bufferPos = 0
            nextPacket = (pnum + 1) and 0xFFFF
            waiting = false
            return null
        }

        if (waiting) { // DATA outside a frame
            return null
        }

        if (pnum != nextPacket) { // lost/reordered packet
            _droppedFrames.incrementAndGet()
            waiting = true
            return null
        }

        val payloadSize = plen
        if (bufferPos + payloadSize > expectedLen) {
            // Should not happen if camera follows protocol, but guard against buffer overflow
            _droppedFrames.incrementAndGet()
            waiting = true
            return null
        }

        System.arraycopy(packet, 7, buffer, bufferPos, payloadSize)
        bufferPos += payloadSize
        nextPacket = (nextPacket + 1) and 0xFFFF

        if (bufferPos >= expectedLen) {
            waiting = true
            return Frame(pts, buffer.copyOf(expectedLen))
        }

        return null
    }
}

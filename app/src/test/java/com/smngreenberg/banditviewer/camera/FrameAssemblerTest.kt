package com.smngreenberg.banditviewer.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameAssemblerTest {

    private fun buildPacket(msg: Int, pnum: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(7 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x55AA.toShort())
        buf.put(msg.toByte())
        buf.putShort(pnum.toShort())
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    private fun buildStartPayload(imgLen: Int, pts: Float): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(imgLen)
        buf.putFloat(pts)
        return buf.array()
    }

    @Test
    fun testReassembleMultiPacketFrame() {
        val assembler = FrameAssembler()
        val jpeg = ByteArray(100) { it.toByte() }
        val pts = 1.23f
        
        val startPkt = buildPacket(0, 10, buildStartPayload(jpeg.size, pts))
        assertNull(assembler.feed(startPkt, startPkt.size))
        
        val data1 = jpeg.copyOfRange(0, 40)
        val data1Pkt = buildPacket(1, 11, data1)
        assertNull(assembler.feed(data1Pkt, data1Pkt.size))
        
        val data2 = jpeg.copyOfRange(40, 100)
        val data2Pkt = buildPacket(1, 12, data2)
        val frame = assembler.feed(data2Pkt, data2Pkt.size)
        
        assertNotNull(frame)
        assertEquals(pts, frame!!.ptsSeconds, 0.001f)
        assertArrayEquals(jpeg, frame.jpeg)
        assertEquals(0, assembler.droppedFrames)
        assertEquals(0, assembler.badPackets)
    }

    @Test
    fun testSinglePacketFrame() {
        val assembler = FrameAssembler()
        val jpeg = ByteArray(20) { it.toByte() }
        val pts = 0.5f
        
        val startPkt = buildPacket(0, 0, buildStartPayload(jpeg.size, pts))
        assertNull(assembler.feed(startPkt, startPkt.size))
        
        val dataPkt = buildPacket(1, 1, jpeg)
        val frame = assembler.feed(dataPkt, dataPkt.size)
        
        assertNotNull(frame)
        assertArrayEquals(jpeg, frame!!.jpeg)
    }

    @Test
    fun testMissingPacketDropsFrame() {
        val assembler = FrameAssembler()
        val startPkt = buildPacket(0, 10, buildStartPayload(100, 1.0f))
        assembler.feed(startPkt, startPkt.size)
        
        // Packet 11 expected, send 12 instead
        val dataPkt = buildPacket(1, 12, ByteArray(50))
        assertNull(assembler.feed(dataPkt, dataPkt.size))
        assertEquals(1, assembler.droppedFrames)
        
        // Next frame should still work
        val startPkt2 = buildPacket(0, 20, buildStartPayload(20, 2.0f))
        assembler.feed(startPkt2, startPkt2.size)
        val dataPkt2 = buildPacket(1, 21, ByteArray(20))
        assertNotNull(assembler.feed(dataPkt2, dataPkt2.size))
    }

    @Test
    fun testPacketNumberWrap() {
        val assembler = FrameAssembler()
        val startPkt = buildPacket(0, 0xFFFF, buildStartPayload(20, 1.0f))
        assembler.feed(startPkt, startPkt.size)
        
        val dataPkt = buildPacket(1, 0, ByteArray(20))
        assertNotNull(assembler.feed(dataPkt, dataPkt.size))
    }

    @Test
    fun testDataBeforeStartIgnored() {
        val assembler = FrameAssembler()
        val dataPkt = buildPacket(1, 10, ByteArray(20))
        assertNull(assembler.feed(dataPkt, dataPkt.size))
        assertEquals(0, assembler.droppedFrames)
    }

    @Test
    fun testBadSync() {
        val assembler = FrameAssembler()
        val pkt = buildPacket(0, 0, buildStartPayload(20, 1.0f))
        pkt[0] = 0x00
        assertNull(assembler.feed(pkt, pkt.size))
        assertEquals(1, assembler.badPackets)
    }

    @Test
    fun testAbsurdLengthRejected() {
        val assembler = FrameAssembler()
        val startPkt = buildPacket(0, 0, buildStartPayload(3_000_000, 1.0f))
        assertNull(assembler.feed(startPkt, startPkt.size))
        assertEquals(1, assembler.badPackets)
    }

    @Test
    fun testPreservesSignedBytes() {
        val assembler = FrameAssembler()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x80.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val startPkt = buildPacket(0, 0, buildStartPayload(jpeg.size, 1.0f))
        assembler.feed(startPkt, startPkt.size)
        
        val dataPkt = buildPacket(1, 1, jpeg)
        val frame = assembler.feed(dataPkt, dataPkt.size)
        assertNotNull(frame)
        assertArrayEquals(jpeg, frame!!.jpeg)
    }
}

package com.smngreenberg.banditviewer.camera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class ViewfinderReceiver {
    @Volatile
    private var socket: DatagramSocket? = null
    private val assembler = FrameAssembler()

    private val _receivedFrames = AtomicInteger(0)
    val receivedFrames: Int get() = _receivedFrames.get()

    val droppedFrames: Int get() = assembler.droppedFrames
    val badPackets: Int get() = assembler.badPackets

    fun open(port: Int) {
        close()
        _receivedFrames.set(0)
        assembler.reset()
        
        val s = DatagramSocket(port)
        s.receiveBufferSize = 1024 * 1024 // 1 MB
        s.soTimeout = 1000 // 1 s
        socket = s
    }

    suspend fun run(onFrame: (FrameAssembler.Frame) -> Unit) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IllegalStateException("Socket not opened")
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)

        try {
            while (isActive && !s.isClosed) {
                try {
                    packet.length = buffer.size
                    s.receive(packet)
                    val frame = assembler.feed(packet.data, packet.length)
                    if (frame != null) {
                        if (isValidJpeg(frame.jpeg)) {
                            _receivedFrames.incrementAndGet()
                            onFrame(frame)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Check isActive and continue
                } catch (e: Exception) {
                    if (!isActive || s.isClosed) break
                    throw BanditException("Video stream error: ${e.message}", e)
                }
            }
        } finally {
            close()
        }
    }

    private fun isValidJpeg(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() &&
               data[data.size - 2] == 0xFF.toByte() && data[data.size - 1] == 0xD9.toByte()
    }

    fun close() {
        socket?.close()
        socket = null
    }
}

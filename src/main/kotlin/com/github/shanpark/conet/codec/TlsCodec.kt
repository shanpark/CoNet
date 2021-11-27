package com.github.shanpark.conet.codec

import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.CoTcp
import com.github.shanpark.conet.TcpCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.Integer.min
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

class TlsCodec(sslContext: SSLContext, clientMode: Boolean): TcpCodec {

    companion object {
        private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    }

    private val sslEngine: SSLEngine = sslContext.createSSLEngine()
    private var isHandshaking: Boolean = false
    private var outAppBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.applicationBufferSize)
    private var outNetBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.packetBufferSize)
    private var inAppBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.applicationBufferSize)
    private var inNetBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.packetBufferSize)

/*
    enum class HandshakeStatus {
        NOT_HANDSHAKING, FINISHED, NEED_TASK, NEED_WRAP, NEED_UNWRAP
    }

    enum class Status {
        BUFFER_UNDERFLOW, BUFFER_OVERFLOW, OK, CLOSED
    }
*/

    init {
        sslEngine.useClientMode = clientMode
    }

    override suspend fun decode(conn: CoTcp, inObj: Any): Any? {
        val buffer = inObj as ReadBuffer
        if (!buffer.isReadable) {
            println("OP_READ occurred but No data received.")
            return null
        }

        println("decode() called [${buffer.readableBytes}]")
        inNetBuffer.clear()
        if (buffer.readableBytes > inNetBuffer.capacity())
            inNetBuffer = ByteBuffer.allocate(buffer.readableBytes)
        buffer.read(inNetBuffer)
        inNetBuffer.flip()

        val sslEngineResult = doUnwrap(conn)
        if (isHandshaking) {
            when (sslEngineResult.status) {
                SSLEngineResult.Status.OK -> {
                    // TODO check handshaking 중에는 inAppBuffer로 나오는 건 아무것도 없어야 맞다.
                    //  따라서 아무것도 할 것도 없다. 이게 맞다면 여기서 inAppBuffer는 비어있어야 한다.
                    assert(!inAppBuffer.hasRemaining())
                }
                SSLEngineResult.Status.CLOSED -> {
                    doShutdown(conn)
                }
                else -> {
                    println("doUnwrap() can return OK or CLOSED")
                    throw SSLException("")
                }
            }

            doHandshake(conn, sslEngineResult.handshakeStatus) // 여기서 나오면 inAppBuffer에 읽혀진 내용이 있을 듯.
        } else {
            when (sslEngineResult.status) { // TODO check 막 줄만 빼고 if문하고 같은데...?
                SSLEngineResult.Status.OK -> {
                }
                SSLEngineResult.Status.CLOSED -> {
                    doShutdown(conn)
                }
                else -> {
                    println("doWrap() can return OK or CLOSED")
                    throw SSLException("")
                }
            }
        }

        val decodec = Buffer(inAppBuffer.remaining())
        decodec.write(inAppBuffer)
        return decodec
    }

    override suspend fun encode(conn: CoTcp, outObj: Any): Any {
        val buffer = outObj as ReadBuffer

        outAppBuffer.clear()
        do {
            val written = min(buffer.readableBytes, buffer.rArray.size - buffer.rOffset)
            outAppBuffer.put(buffer.rArray, buffer.rOffset, written)
            buffer.rSkip(written)
        } while (buffer.isReadable)
        outAppBuffer.flip()

        var sslEngineResult = doWrap(conn)
        if (isHandshaking) { // handshaking이 시작되었다. handshking이 시작되면 outAppBuffer의 내용은 handshaking이 끝날 때까지 그대로 있다.
            when (sslEngineResult.status) {
                SSLEngineResult.Status.OK -> {
                    rawWrite(conn, outNetBuffer)
                }
                SSLEngineResult.Status.CLOSED -> {
                    doShutdown(conn)
                }
                else -> {
                    println("doWrap() can return OK or CLOSED")
                    throw SSLException("")
                }
            }

            doHandshake(conn, sslEngineResult.handshakeStatus)

            // TODO handshaking이 제대로 끝났는지 봐야하지 않나?

            sslEngineResult = doWrap(conn)
        }

        when (sslEngineResult.status) { // TODO check 막 줄만 빼고 if문하고 같은데...?
            SSLEngineResult.Status.OK -> {
                // outNetBuffer 반환.
                outNetBuffer.flip()
                val outBuffer = Buffer(outNetBuffer.remaining())
                outBuffer.write(outNetBuffer)
                outNetBuffer.clear()
                return outBuffer
            }
            SSLEngineResult.Status.CLOSED -> {
                doShutdown(conn)
            }
            else -> {
                throw SSLException("doWrap() can return OK or CLOSED")
            }
        }

        return Buffer(0) // TODO Buffer.EMPTY
    }

    private suspend fun doHandshake(conn: CoTcp, handshakeStatus0: SSLEngineResult.HandshakeStatus) {
        var sslEngineResult: SSLEngineResult
        var handshakeStatus = handshakeStatus0

        while (true) {
            when (handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    println("decode handshakeStatus: NEED_WRAP")
                    sslEngineResult = doWrap(conn)
                    when (sslEngineResult.status) {
                        SSLEngineResult.Status.OK -> {
                            handshakeStatus = sslEngineResult.handshakeStatus
                            rawWrite(conn, outNetBuffer)
                        }
                        SSLEngineResult.Status.CLOSED -> {
                            doShutdown(conn)
                        }
                        else -> {
                            throw SSLException("doWrap() can return OK or CLOSED")
                        }
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    println("decode handshakeStatus: NEED_UNWRAP")
                    sslEngineResult = doUnwrap(conn)
                    when (sslEngineResult.status) {
                        SSLEngineResult.Status.OK -> {
                            handshakeStatus = sslEngineResult.handshakeStatus
                            // TODO 읽은 데이터가 inAppBuffer에 있을텐데?? 어떻게 처리하지?
                            //  그대로 두면 encode에서 알아서 처리할 수 있을 것 같기도 한데...
                        }
                        SSLEngineResult.Status.CLOSED -> {
                            doShutdown(conn)
                        }
                        else -> {
                            throw SSLException("doUnwrap() can return OK or CLOSED")
                        }
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    println("decode handshakeStatus: NEED_TASK")
                    doTask()
                    handshakeStatus = sslEngine.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.FINISHED -> {
                    println("decode handshakeStatus: FINISHED")
                    isHandshaking = false
                    break
                }
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    println("decode handshakeStatus: NOT_HANDSHAKING")
                    isHandshaking = false
                    break
                }
                else -> {
                    throw SSLException("Unknown SSLEngineResult handshake status.")
                }
            }
        }
    }

    private fun doWrap(conn: CoTcp): SSLEngineResult {
        var sslEngineResult: SSLEngineResult
        while (true) {
            sslEngineResult = sslEngine.wrap(outAppBuffer, outNetBuffer)
            println("sslEngine.wrap() consumed = ${sslEngineResult.bytesConsumed()}")
            isHandshaking = (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED) &&
                (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)

            when (sslEngineResult.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val appSize: Int = sslEngine.session.packetBufferSize
                    val newBuf = ByteBuffer.allocate(appSize + outNetBuffer.position())
                    outNetBuffer.flip()
                    newBuf.put(outNetBuffer)
                    outNetBuffer = newBuf
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    throw SSLException("Buffer underflow occured after a wrap.")
                }
                SSLEngineResult.Status.OK -> {
                    break
                }
                SSLEngineResult.Status.CLOSED -> {
                    break
                }
                else -> {
                    throw SSLException("Unknown SSLEngineResult status.")
                }
            }
        }

        return sslEngineResult
    }

    private fun doUnwrap(conn: CoTcp): SSLEngineResult {
        var sslEngineResult: SSLEngineResult
        while (true) {
            if (conn.channel.read(inNetBuffer) < 0) {
                return SSLEngineResult(SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0)
            }
            inNetBuffer.flip()

            sslEngineResult = sslEngine.unwrap(inNetBuffer, inAppBuffer)
            inNetBuffer.compact()

            isHandshaking = (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED) &&
                    (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)

            when (sslEngineResult.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val appSize: Int = sslEngine.session.applicationBufferSize
                    val newBuf = ByteBuffer.allocate(appSize + inAppBuffer.position())
                    inAppBuffer.flip()
                    newBuf.put(inAppBuffer)
                    inAppBuffer = newBuf
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    val appSize: Int = sslEngine.session.packetBufferSize
                    val newBuf = ByteBuffer.allocate(appSize + inNetBuffer.position())
                    inNetBuffer.flip()
                    newBuf.put(inNetBuffer)
                    inNetBuffer = newBuf
                    continue // read again
                }
                SSLEngineResult.Status.OK -> {
                    break
                }
                SSLEngineResult.Status.CLOSED -> {
                    break
                }
                else -> {
                    throw SSLException("Unknown SSLEngineResult status.")
                }
            }
        }

        return sslEngineResult
    }

    private suspend fun doTask() {
        println("doTask before")
        val job = ioScope.launch {
            var task = sslEngine.delegatedTask
            while (task != null) {
                task.run()
                task = sslEngine.delegatedTask
            }
            println("doTask end")
        }
        job.join()
        println("doTask after")
    }

    private suspend fun doShutdown(conn: CoTcp) {
        println("decode doShutdown()")
        // Indicate that application is done with engine
        sslEngine.closeOutbound()

        while (!sslEngine.isOutboundDone) {
            // Get close message
            val sslEngineResult: SSLEngineResult = sslEngine.wrap(ByteBuffer.wrap(ByteArray(0)), outNetBuffer) // TODO ByteArray.EMPTY 싱글턴 고려.
            // TODO Check sslEngineResult statuses

            // Send close message to peer
            rawWrite(conn, outNetBuffer)
        }

        // Close transport
        conn.channel.close()
    }

    private suspend fun rawWrite(conn: CoTcp, byteBuffer: ByteBuffer) { // TODO check parameter가 무조건 outNetBuffer인 것 같은데...
        val job = ioScope.launch {
            byteBuffer.flip()
            while (byteBuffer.hasRemaining()) {
                conn.channel.write(byteBuffer)
            }
            byteBuffer.clear()
        }
        job.join()
   }
}
package com.github.shanpark.conet.codec

import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.CoTcp
import com.github.shanpark.conet.TcpCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/* TODO
 *  - IO scope에서 실행해야 하는 로직들 검토해서 다시 재정비 해야함.
 *
 */
/**
 *
 */
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

        var nextBuffer: Buffer? = null
        while (true) {
            buffer.read(inNetBuffer) // net buffer로 옮김

            val sslEngineResult = doUnwrap() // unwrap 처리
            if (isHandshaking) {
                when (sslEngineResult.status) {
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        // handshaking이 시작되었다는 건 BUFFER_UNDERFLOW는 일어날 수 없다는 뜻이다.
                    }
                    SSLEngineResult.Status.OK -> {
                        // TODO check handshaking 중에는 inAppBuffer로 나오는 건 아무것도 없어야 맞다.
                        //  따라서 아무것도 할 것도 없다. 이게 맞다면 여기서 inAppBuffer는 비어있어야 한다.
                        assert(inAppBuffer.position() == 0)
                    }
                    SSLEngineResult.Status.CLOSED -> {
                        doShutdown(conn)
                        break // 여기서 빠져 나가야 한다.
                    }
                    else -> {
                        println("doUnwrap() can return OK or CLOSED")
                        throw SSLException("")
                    }
                }

                doHandshake(conn, sslEngineResult.handshakeStatus) // 여기서 나오면 inAppBuffer에 읽혀진 내용이 있을 듯.
            }

            when (sslEngineResult.status) { // TODO check 막 줄만 빼고 if문하고 같은데...?
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    // buffer에 읽을 게 남았다면 다시 inNetBuffer가 채워지고 다시 doUnwrap()이 호출될 것이다.
                    if (!buffer.isReadable) { // underflow이고 더 이상 읽을 게 없다면
                        buffer.invalidateMark() // inNetBuffer로 모두 옮겨졌기 때문에 다시 복원되지 못하도록 mark를 invalidate해야 한다.
                        return nextBuffer
                    }
                }
                SSLEngineResult.Status.OK -> {
                    inAppBuffer.flip()
                    if (nextBuffer == null)
                        nextBuffer = Buffer(inAppBuffer.remaining())
                    nextBuffer.write(inAppBuffer)
                    inAppBuffer.clear() // 모두 옮겨졌으므로 clear()해도 괜찮다.
                }
                SSLEngineResult.Status.CLOSED -> {
                    doShutdown(conn)
                    break // TODO 여기서도 빠져나가면 되나?
                }
                else -> {
                    println("doWrap() can return OK or CLOSED")
                    throw SSLException("")
                }
            }

            // unwrap할 데이터도 없고 inNetBuffer로 추가 복사할 데이터도 없으면 그만. inNetBuffer에 조금 잘린 데이터가 있다면
            // 다음 시도에서 underflow가 발생해서 중단될 것이다.
            if ((inNetBuffer.position() == 0) && !buffer.isReadable)
                break
        }

        return nextBuffer
    }

    override suspend fun encode(conn: CoTcp, outObj: Any): Any {
        val buffer = outObj as ReadBuffer

        val nextBuffer = Buffer()
        while (true) {
            buffer.read(outAppBuffer)

            var sslEngineResult = doWrap(conn)
            if (isHandshaking) { // handshaking이 시작되었다. handshking이 시작되면 outAppBuffer의 내용은 handshaking이 끝날 때까지 그대로 있다.
                when (sslEngineResult.status) {
                    SSLEngineResult.Status.OK -> {
                        rawWrite(conn, outNetBuffer)
                    }
                    SSLEngineResult.Status.CLOSED -> {
                        doShutdown(conn)
                        break // 여기서 그냥 빠져나가도 되나?
                    }
                    else -> {
                        throw SSLException("doWrap() can return OK or CLOSED")
                    }
                }

                doHandshake(conn, sslEngineResult.handshakeStatus)

                // 여기까지 왔으면 handshaking은 끝난 것이다.
                // TODO handshaking이 제대로 끝났는지 봐야하지 않나?

                sslEngineResult = doWrap(conn) // handshking 중에는 app data는 전혀 전송되지 않는다. 따라서 원래 보내려던 데이터가 outAppBuffer에 그대로 있다.
            }

            when (sslEngineResult.status) { // TODO check 막 줄만 빼고 if문하고 같은데...?
                SSLEngineResult.Status.OK -> {
                    // outNetBuffer 반환.
                    outNetBuffer.flip()
                    nextBuffer.write(outNetBuffer)
                    outNetBuffer.clear()
                }
                SSLEngineResult.Status.CLOSED -> {
                    doShutdown(conn)
                    break // 여기서 그냥 빠져나가도 되나?
                }
                else -> {
                    throw SSLException("doWrap() can return OK or CLOSED")
                }
            }

            // wrap할 데이터도 없고 outAppBuffer로 추가 복사할 데이터도 없으면 그만.
            if ((outAppBuffer.position() == 0) && !buffer.isReadable)
                break
        }

        return nextBuffer
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
                    sslEngineResult = doUnwrapForHandshake(conn)
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
            outAppBuffer.flip()
            sslEngineResult = sslEngine.wrap(outAppBuffer, outNetBuffer)
            outAppBuffer.compact()
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

    private fun doUnwrap(): SSLEngineResult {
        var sslEngineResult: SSLEngineResult

        while (true) {
            inNetBuffer.flip()
            sslEngineResult = sslEngine.unwrap(inNetBuffer, inAppBuffer)
            inNetBuffer.compact()
            isHandshaking = (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED) &&
                    (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
            when (sslEngineResult.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    println("doUnwrap() -> BUFFER_OVERFLOW")
                    val appSize: Int = sslEngine.session.applicationBufferSize
                    val newBuf = ByteBuffer.allocate(appSize + inAppBuffer.position())
                    inAppBuffer.flip()
                    newBuf.put(inAppBuffer)
                    inAppBuffer = newBuf
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    println("doUnwrap() -> BUFFER_UNDERFLOW")
                    val netSize: Int = sslEngine.session.packetBufferSize
                    if (netSize > inAppBuffer.capacity()) { // reference 문서에 이렇게 되어있는데 좀 이상하네.. dst 크기보다 큰데 src를 늘리네..
                        val newBuf = ByteBuffer.allocate(netSize)
                        inNetBuffer.flip()
                        newBuf.put(inNetBuffer)
                        inNetBuffer = newBuf
                    }
                    break // 여기서 끝내면 호출자가 다시 버퍼를 채워서 호출할 것이다.
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

    /**
     * handshaking 하는 동안 호출된다. 데이터가 모자라면 직접 소켓으로부터 읽어들인다.
     */
    private fun doUnwrapForHandshake(conn: CoTcp): SSLEngineResult {
        var sslEngineResult: SSLEngineResult

        // inNetBuffer에는 이미 data가 있을 수도 있다. 그런 경우 1 바이트도 못 읽고 지나가는 경우가 있는 데 정상이다.
        if (conn.channel.read(inNetBuffer) < 0)
            throw SSLException("The socket was closed during NEED_UNWRAP processing.")

        while (true) {
            inNetBuffer.flip()
            sslEngineResult = sslEngine.unwrap(inNetBuffer, inAppBuffer)
            inNetBuffer.compact()
            isHandshaking = (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED) &&
                    (sslEngineResult.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
            when (sslEngineResult.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    println("doUnwrap() -> BUFFER_OVERFLOW")
                    val appSize: Int = sslEngine.session.applicationBufferSize
                    val newBuf = ByteBuffer.allocate(appSize + inAppBuffer.position())
                    inAppBuffer.flip()
                    newBuf.put(inAppBuffer)
                    inAppBuffer = newBuf
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    println("doUnwrap() -> BUFFER_UNDERFLOW")
                    val netSize: Int = sslEngine.session.packetBufferSize
                    if (netSize > inAppBuffer.capacity()) { // reference 문서에 이렇게 되어있는데 좀 이상하네.. dst 크기보다 큰데 src를 늘리네..
                        val newBuf = ByteBuffer.allocate(netSize)
                        inNetBuffer.flip()
                        newBuf.put(inNetBuffer)
                        inNetBuffer = newBuf
                    }
                    // BUFFER_UNDERFLOW 에서는 적어도 1 바이트 이상 읽어야 계속하는 게 의미가 있다.
                    while (true) {
                        val read = conn.channel.read(inNetBuffer)
                        if (read < 0)
                            throw SSLException("The socket was closed during NEED_UNWRAP processing.")
                        else if (read > 0)
                            break
                    }
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

            doHandshake(conn, sslEngineResult.handshakeStatus) // shutdown을 여기서 끝낸다. 따라서 이후는 더 이상 read/write하면 안된다.
        }

        // Close transport
        conn.channel.close() // TODO 직접 닫으면 안되지.. conn에게 요청해야지..
    }

    private fun rawRead(conn: CoTcp, byteBuffer: ByteBuffer): Int { // inNetBuffer
        val read = conn.channel.read(byteBuffer)
        byteBuffer.flip()
        return read
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
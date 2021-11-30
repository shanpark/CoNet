package com.github.shanpark.conet.codec

import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.CoTcp
import com.github.shanpark.conet.TcpCodec
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * TlsCodec은 handshaking을 IO scope에서 처리하도록 한다. handshaking 중에는 non-blocking socket에 직접
 * read하거나 write하는 작업이 들어가는데 이 때 loop를 돌며 데이터가 read 또는 write될 때 까지 대기를 하도록 구현된다.
 * 이러한 작업들이 현재 coroutine을 잡고 있을 수는 없으므로 IO scope로 넘겨서 실행되도록 하고 현재 coroutine은 suspend되어
 * 다른 channel의 이벤트를 계속해서 처리할 수 있도록 한다. handshaking 중이 아닌 곳에서 직접 socket write 작업을
 * 수행하는 경우(rawWrite() 호출)에도 IO scope에 실행되도록 구현하였다.
 * IO scope라고 하더라도 socket에서 1 바이트도 읽거나 쓸 수 없는 상황이 오면 yield()를 호출하여 다른 coroutine에게 일단
 * 양보하는 방식으로 구현한다.
 *
 * 한 channel당 하나의 coroutine이 수행되는데 굳이 IO scope로 보낼 필요가 있나?? yield만 잘해주면 되는 거 아닌가?
 * default scope는 channel 들의 coroutine을 수행하도록 하고 io scope는 시간이 걸리는 작업을 수행하도록 하면 default scope가
 * 좀 원활해질까?
 *
 * handshaking 중에는 inbound든 outbound든 app 데이터가 전혀 사용(consume)되지 않는다. handshaking이 끝나고 나서
 * outNetBuffer, inAppBuffer에 데이터가 생성되어 있다면 다음 코덱에서 처리할 수 있도록 넘겨주면 된다.
 */
@Suppress("BlockingMethodInNonBlockingContext")
class TlsCodec(sslContext: SSLContext, clientMode: Boolean): TcpCodec {

    companion object {
        private val EMPTY_BUFFER = ByteBuffer.wrap(ByteArray(0))
    }

    private val sslEngine: SSLEngine = sslContext.createSSLEngine()
    private var isHandshaking: Boolean = false // handshaking 진행중 표시
    private var isShutingDown: Boolean = false // shutdown 진행중 표시

    // outbound 시에 outAppBuffer -> wrap() -> outNetBuffer 순서로 데이터가 흘러간다.
    private var outAppBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.applicationBufferSize)
    private var outNetBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.packetBufferSize)

    // inbound 시에 inNetBuffer -> unwrap() -> inAppBuffer 순서로 데이터가 흘러간다.
    private var inAppBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.applicationBufferSize)
    private var inNetBuffer: ByteBuffer = ByteBuffer.allocate(sslEngine.session.packetBufferSize)

    private var inBuffer: Buffer = Buffer() // inbound 시에 다음 codec으로 넘겨줄 data를 저장한 buffer이다. 사용되지 않으면 누적된다.

    init {
        sslEngine.useClientMode = clientMode
    }

    override suspend fun decode(conn: CoTcp, inObj: Any): Any? {
        val buffer = inObj as ReadBuffer
        if (!buffer.isReadable) // 추가 데이터가 없다면
            return null // codec chain을 진행할 필요가 없다.

        while (true) {
            buffer.read(inNetBuffer) // net buffer로 옮김. inNetBuffer의 크기만큼만 읽어서 unwrap을 시도한다. 나머지는 다음 loop에서 처리.

            var sslEngineResult = doUnwrap() // unwrap 처리
            if (isHandshaking) { // 실제 handshaking할 때 뿐만 아니라 shutdown할 때도 handshaking 상태로 나온다.
                when (sslEngineResult.status) {
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        // handshaking중에 BUFFER_UNDERFLOW가 발생했다면  socket에서 직접 읽어서 채워야 한다.
                        sslEngineResult = doUnwrapForHandshake(conn) // unwrap 처리
                    }
                    SSLEngineResult.Status.OK -> {
                        // handshaking 중에는 inAppBuffer로 데이터가 app data가 생성되는 건 아무것도 없다.
                        // 따라서 inAppBuffer는 비어있어야 한다.
                    }
                    SSLEngineResult.Status.CLOSED -> { // shutdown 메시지가 수신되면 status는 CLOSED로 바뀐다.
                        doShutdown(conn)
                        break // shutdown하고 loop를 빠져나가야 한다.
                    }
                    else -> {
                        throw SSLException("doUnwrap() can return BUFFER_UNDERFLOW, OK or CLOSED")
                    }
                }

                doHandshake(conn, sslEngineResult.handshakeStatus) // 본격 handshake는 여기서 진행된다.
                // 여기까지 왔으면 handshaking은 정상적으로 끝난 것이다.
                // 실패한다면 exception이 발생해서 에러 처리로 빠진다.

                // 단지 handshaking만 했을 수도 있고 뭔가 데이터를 보내면서 handshaking이 일어났을 수도 있다.
                // 방금 handshaking이 끝났는데 inNetBuffer에 데이터가 남아있다면 이건 app을 위한 데이터가 남아있는 것이다.
                // 즉시 unwrap()을 하고 진행하면 된다.
                if (inNetBuffer.position() > 0)
                    sslEngineResult = doUnwrap() // unwrap 처리
            }

            when (sslEngineResult.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> { // 입력이 더 있어야 unwrap이 가능하다
                    // buffer(inObj)에 읽을 게 남았다면 다시 inNetBuffer가 채워지고 다시 doUnwrap()이 호출될 것이다.
                    if (!buffer.isReadable) { // 하지만 더 이상 읽을 게 없다면
                        buffer.invalidateMark() // inNetBuffer로 모두 옮겨졌기 때문에 다시 복원되지 못하도록 mark를 invalidate해야 한다.
                        return inBuffer
                    }
                }
                SSLEngineResult.Status.OK -> {
                    inAppBuffer.flip()
                    inBuffer.write(inAppBuffer)
                    inAppBuffer.clear() // 모두 옮겨졌으므로 clear()해도 괜찮다.
                }
                SSLEngineResult.Status.CLOSED -> {
                    if (inAppBuffer.position() > 0) {
                        inAppBuffer.flip()
                        inBuffer.write(inAppBuffer)
                        inAppBuffer.clear() // 모두 옮겨졌으므로 clear()해도 괜찮다.
                    }
                    doShutdown(conn)
                    break // shutdown하고 loop를 빠져나가야 한다.
                }
                else -> {
                    throw SSLException("doUnwrap() can return BUFFER_UNDERFLOW, OK or CLOSED")
                }
            }

            // inNetBuffer에 unwrap할 데이터도 없고 inNetBuffer로 추가 복사할 데이터도 없으면 그만.
            // inNetBuffer에 조금 잘린 데이터가 있더라도 다음 시도에서 underflow가 발생해서 중단될 것이다.
            if ((inNetBuffer.position() == 0) && !buffer.isReadable)
                break
        }

        return inBuffer
    }

    override suspend fun encode(conn: CoTcp, outObj: Any): Any {
        val buffer = outObj as ReadBuffer

        val nextBuffer = Buffer()
        while (true) {
            buffer.read(outAppBuffer) // outAppBuffer 크기만큼만 읽어온다. 나머지는 그 다음 loop에서 시도할 것이다.

            var sslEngineResult = doWrap()
            if (isHandshaking) { // handshaking이 시작되었다. 일단 handshaking이 시작되면 if문 안에서 handshking이 끝날 때까지 못나감.
                                 // handshking중에는 outAppBuffer의 내용은 handshaking이 끝날 때까지 그대로 있다. 즉 wrap이 전혀 안된채로 있을 것이다.
                when (sslEngineResult.status) {
                    SSLEngineResult.Status.OK -> {
                        coroutineScope {
                            // outNetBuffer의 내용을 전송. handshaking data는 여기서 socket에 직접 write한다.
                            launch(Dispatchers.IO) { rawWrite(conn) }
                        }
                    }
                    SSLEngineResult.Status.CLOSED -> {
                        doShutdown(conn) // encode()이면 내가 write 작업중인데 CLOSED가 발생한 것이므로 아직 보내지 못한게 있더라도
                                         // 바로 shutdown해도 문제가 없다. (peer는 받을 의사가 없는 상태임.)
                        return Buffer.EMPTY
                    }
                    else -> {
                        throw SSLException("doWrap() can return OK or CLOSED")
                    }
                }

                doHandshake(conn, sslEngineResult.handshakeStatus) // 본격 handshaking.
                // 여기까지 왔으면 handshaking은 정상적으로 끝난 것이다.
                // 실패한다면 exception이 발생해서 에러 처리로 빠진다.
                sslEngineResult = doWrap() // handshking 중에는 app data는 전혀 전송되지 않는다. 따라서 원래 보내려던 데이터가
                                           // outAppBuffer에 그대로 있다. 따라서 다시 wrap을 시도해줘야 한다.
            }

            when (sslEngineResult.status) {
                SSLEngineResult.Status.OK -> {
                    // outNetBuffer 반환.
                    outNetBuffer.flip()
                    nextBuffer.write(outNetBuffer)
                    outNetBuffer.clear()
                }
                SSLEngineResult.Status.CLOSED -> {
                    if (outNetBuffer.position() > 0) { // 여기서 outNetBuffer에 wrap이 된 데이터가 있다면 보낼 수 있도록 한다.
                        outNetBuffer.flip()
                        nextBuffer.write(outNetBuffer)
                        outNetBuffer.clear()
                    }
                    doShutdown(conn) // encode()이면 내가 write 작업중인데 CLOSED가 발생한 것이므로 아직 보내지 못한게 있더라도
                                     // 바로 shutdown해도 문제가 없다. (peer는 받을 의사가 없는 상태임.)
                    return Buffer.EMPTY
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

    /**
     * 연결 해제 요청을 받으면 호출된다.
     * 연결 해제 요청은 외부 stop() 요청에 의해서 발생할 수도 있고 OP_READ 처리중 end of stream이 감지되어 발생할
     * 수도 있고 내부에서 handshaking 처리 중에 channel이 닫혀서 end of stream이 감지되어 요청할 수도 있다.
     *
     * 외부에서 요청된 경우에는 여기서 shutdown 작업을 시작 시키겠지만 내부에서 요청되었다면 이미 shutdown이 완료된 후에
     * close() 요청에 의해 여기로 오게된다. OP_READ 처리중 end of stream이 감지되어 여기로 오게되면 closeInbound()를
     * 호출해 주어야 한다.
     * shutdown이 완료되었는 지를 판단하는 건 sslEngine.isOutboundDone를 통해서 알 수 있다.
     *
     * codec 내의 모든 메소드는 모두 하나의 coroutine내에서 serial하게 실행되는 것이 보장 된다.
     * 따라서 shutdown 작업 중에 이 메소드가 호출되는 경우는 없으므로 sslEngine.isOutboundDone 상태로
     * shutdown 상태를 알아보는 게 아무런 문제가 되지 않는다.
     */
    override suspend fun onClose(conn: CoTcp) {
        if (!sslEngine.isOutboundDone) { // isOutboundDone이면 이미 shutdown된 상태라고 볼 수 있다.
            if (conn.eosDetected) { // eos가 확실히 감지된 경우
                try {
                    sslEngine.closeInbound() // 이미 socket 접속이 끊어진 걸로 판단된 상태라면 sslEngine에 알려준다.
                    // closeInbound()가 호출된 이후에 (handshake, shutdown 처리용) 데이터 가 도착하면
                    // SSLException(internal_error)이 발생한다. 그래서 eos가 확실히 감지된 경우에만 호출해야 한다.
                } catch (e: SSLException) {
                    // ignore 'without close_notify msg' exception
                }
            }

            doShutdown(conn)
        }
    }

    private suspend fun doHandshake(conn: CoTcp, handshakeStatus0: SSLEngineResult.HandshakeStatus) = coroutineScope {
        launch(Dispatchers.IO) { // handshaking은 통째로 IO scope에서 실행한다. 다른 socket 작업을 중단시키지 않도록.
            var sslEngineResult: SSLEngineResult
            var handshakeStatus = handshakeStatus0

            while (true) {
                when (handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        sslEngineResult = doWrap()
                        when (sslEngineResult.status) {
                            SSLEngineResult.Status.OK -> {
                                handshakeStatus = sslEngineResult.handshakeStatus
                                rawWrite(conn) // outNetBuffer의 내용을 전송. handshaking 데이터는 여기서 직접 전송해준다.
                            }
                            SSLEngineResult.Status.CLOSED -> {
                                doShutdown(conn)
                                break
                            }
                            else -> {
                                throw SSLException("doWrap() can return OK or CLOSED")
                            }
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        sslEngineResult = doUnwrapForHandshake(conn)

                        when (sslEngineResult.status) {
                            SSLEngineResult.Status.OK -> {
                                handshakeStatus = sslEngineResult.handshakeStatus
                                // handshaking 중에는 app data가 생성되는 게 하나도 없다.
                                // 따라서 inAppBuffer는 handshaking이 끝날 때 까지 비어있는 게 맞다.
                            }
                            SSLEngineResult.Status.CLOSED -> { // shutdown중에 doHandshake()가 호출된 경우에도 CLOSED가 나온다.
                                doShutdown(conn) // 하지만 이미 shutdown중이므로 다시 호출했을 때 문제 없도록 doShutdown()에서 처리한다.
                                break
                            }
                            else -> {
                                throw SSLException("doUnwrap() can return OK or CLOSED")
                            }
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        doTask()
                        handshakeStatus = sslEngine.handshakeStatus
                    }
                    SSLEngineResult.HandshakeStatus.FINISHED -> {
                        isHandshaking = false
                        break
                    }
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                        isHandshaking = false
                        break
                    }
                    else -> {
                        throw SSLException("Unknown SSLEngineResult handshake status.")
                    }
                }
            }
        }
    }


    private fun doWrap(): SSLEngineResult {
        var sslEngineResult: SSLEngineResult
        while (true) {
            outAppBuffer.flip()
            sslEngineResult = sslEngine.wrap(outAppBuffer, outNetBuffer)
            outAppBuffer.compact()

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
                    val appSize: Int = sslEngine.session.applicationBufferSize
                    val newBuf = ByteBuffer.allocate(appSize + inAppBuffer.position())
                    inAppBuffer.flip()
                    newBuf.put(inAppBuffer)
                    inAppBuffer = newBuf
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
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
    private suspend fun doUnwrapForHandshake(conn: CoTcp): SSLEngineResult {
        var sslEngineResult: SSLEngineResult

        // inNetBuffer에는 이미 data가 있을 수도 있다. 그런 경우 1 바이트도 못 읽고 지나가는 경우가 있는 데 정상이다.
        if (conn.channel.read(inNetBuffer) < 0) {
            sslEngine.closeInbound()
            return SSLEngineResult(SSLEngineResult.Status.CLOSED, sslEngine.handshakeStatus, 0, 0)
        }

        HANDSHAKE_LOOP@
        while (true) {
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
                        if (read < 0) {
                            sslEngine.closeInbound()
                            return SSLEngineResult(SSLEngineResult.Status.CLOSED, sslEngine.handshakeStatus, 0, 0)
                        } else if (read > 0) {
                            break
                        } else {
                            yield() // 1 바이트도 못읽었다면 다른 coroutine에게 잠시 양보한다.
                        }
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

    private suspend fun doShutdown(conn: CoTcp) {
        if (!isShutingDown) {
            isShutingDown = true

            sslEngine.closeOutbound() // 여기서는 closeOutbound()만 해준다. closeInbound()는 EoS가 감지된 쪽에서 해준다.

            while (!sslEngine.isOutboundDone) {
                // Get close message
                val sslEngineResult = sslEngine.wrap(EMPTY_BUFFER, outNetBuffer) // isOutboundDone이 될 때 까지 계속 wrap을 해서 상대에게 보내야 한다.
                // macOS에서는 여기서 close_notify건 그 응답이건 CLOSED, NOT_HANDSHAKING이 나온다.
                // linux에서는 close_notify인 경우 CLOSED, NEED_UNWRAP이 나온다. notify의 응답을 받아야 하기 때문이다.
                // 규격상으로 둘 다 허용가능한 건지 모르겠지만 notify를 보냈으면 응답을 받아야 하므로 linux가 맞는 것 같다.

                // 바로 위의 wrap()에서 생성된 message를 peer에게 보낸다.
                // 내가 닫는 경우라는 shutdown 요청이고, 상대가 shutdown 요청을 해서 시작된 거라면 shutdown 요청에 대한 응답이 될 것이다.
                coroutineScope {
                    // outNetBuffer의 내용을 전송. shutdown 메시지는 직접 socket으로 전송해준다.
                    launch(Dispatchers.IO) { rawWrite(conn) }
                }

                // 상대가 shutdown을 했으면 status: CLOSED, handshakeStatus: NOT_HANDSHAKING
                // 내가 shutdown을 요청 했으면 status: CLOSED, handshakeStatus: NEED_UNWRAP. shutdown 응답을 unwrap해야 한다.
                // 하지만 macOS에서는 둘 다 NOT_HANDSHAKING이 나오는 현상이 있다.
                // shutdown도 로직은 handshaking과 마찬가지. 여기서 끝난다. 이후는 더 이상 read/write하면 안된다.
                doHandshake(conn, sslEngineResult.handshakeStatus)
            }

            // Close transport
            conn.close() // 직접 닫지 않고 conn으로 요청한다.
            isShutingDown = false
        }
    }

    /**
     * SSLEngine에서 위탁한 task들을 실행한다.
     * 어떤 작업인지는 알 수 없으므로 IO scope에서 실행되도록 호출하는 게 안전하다.
     * 현재는 doHandshake()에서만 사용되고 있으며 doHandshake()는 전체가 IO scope에서 수행되므로 문제 없다.
     */
    private fun doTask() {
        var task = sslEngine.delegatedTask
        while (task != null) {
            task.run()
            task = sslEngine.delegatedTask
        }
    }

    /**
     * channel을 통해서 직접 peer로 byteBuffer의 내용을 모두 전송한다.
     * 내부에서 알아서 flip()과 clear()를 호출하므로 함수 호출자는 따로 호출해서는 안된다.
     * buffer에 담긴 내용을 모두 전송하거나 exception이 발생할 떄 까지 종료되지 않는다.
     *
     * 내부적으로 loop를 돌며 모두 전송될 때 까지 재시도하는 로직이 있다. 따라서 IO scope에서 실행되도록 호출하는 게 안전하다.
     */
    private suspend fun rawWrite(conn: CoTcp) {
        outNetBuffer.flip()
        while (outNetBuffer.hasRemaining()) {
            conn.channel.write(outNetBuffer)
            if (outNetBuffer.hasRemaining())
                yield() // 모두 write가 안됐다면 커널의 버퍼가 가득 찼을 것이다. 이 경우 즉시 다른 coroutine이 실행되도록 양보한다.
        }
        outNetBuffer.clear() // 모두 전송했으므로 clear해준다.
    }
}
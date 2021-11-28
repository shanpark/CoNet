package com.github.shanpark.conet

import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.util.Event
import com.github.shanpark.conet.util.EventId
import com.github.shanpark.conet.util.off
import com.github.shanpark.conet.util.on
import com.github.shanpark.services.coroutine.CoroutineService
import com.github.shanpark.services.coroutine.EventLoopCoTask
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import kotlin.math.min

/**
 * socket connection을 wrapping하여 coroutine 서비스를 구현하는 클래스
 * 사용자가 직접 생성할 일은 없으며 CoClient 나 CoServer 객체를 통해서 생성된다.
 *
 * @param channel socketChannel 객체. CoSelectable 인터페이스 구현을 위해서 필요하다.
 * @param handlers connection에서 발생하는 이벤트 처리를 구현한 CoHandlers 객체.
 */
open class CoTcp(final override val channel: SocketChannel, val handlers: CoHandlers<CoTcp>): CoSelectable {
    /**
     * CoSelectable 인터페이스 구현.
     */
    override lateinit var selectionKey: SelectionKey

    private var task = EventLoopCoTask(::onEvent, handlers.idleTimeout, ::onIdle, ::onError)
    protected val service = CoroutineService().start(task)

    private val inBuffer: Buffer = Buffer()
    private val outBuffers = mutableListOf<ReadBuffer>()

    init {
        channel.configureBlocking(false)
    }

    /**
     * CoServer에서 새로운 접속을 accept()한 후 CoConnection객체를 생성하고 호출해주는 메소드이다.
     * 접속이 맺어진 후 최초에 한 번만 호출된다.
     */
    internal suspend fun connected() {
        task.sendEvent(Event.CONNECTED)
    }

    /**
     * peer로 보낼 객체를 write한다.
     * 어떤 객체든지 상관없지만 CoHandlers 객체에 구성된 codec chain을 거쳐서 최종적으로 ReadBuffer 객체로
     * 변환되어야 한다.
     * ReadBuffer 객체를 write하면 buffer의 내용은 모두 읽혀진다.
     *
     * @param outObj peer로 보낼 데이터 객체.
     */
    suspend fun write(outObj: Any) {
        if (outObj is ReadBuffer)
            task.sendEvent(Event.newEvent(EventId.WRITE, outObj.readSlice(outObj.readableBytes)))
        else
            task.sendEvent(Event.newEvent(EventId.WRITE, outObj))
    }

    /**
     * 현재 접속을 닫도록 요청한다.
     * 이미 닫혀진 상태에서는 호출해서는 안된다.
     */
    suspend fun close() {
        task.sendEvent(Event.CLOSE)
    }

    /**
     * 사용자 이벤트를 전송한다.
     * CoHandlers 의 onUserHandler가 호출된다. 다른 이벤트와 마찬가지로 coroutine 서비스 내에서 실행된다.
     *
     * @param param onUserHandler 로 전달되는 parameter 객체.
     */
    suspend fun sendUserEvent(param: Any?) {
        task.sendEvent(Event.newUserEvent(param))
    }

    /**
     * CoSelectable 인터페이스 구현.
     * CoSelector의 thread에서만 호출되며 Selector에 OP_READ, OP_WRITE, OP_CONNECT가 발생하면 호출된다.
     *
     * 내부 coroutine 서비스에서 처리하도록 하기 때문에 일단 channel에 해당 이벤트가 다시 발생하지 않도록
     * 처리를 즉시해야 하고 가능한 빨리 리턴하는 것이 좋다.
     * 내부적으로 발생하는 모든 exception은 전파되어서는 안되고 반드시 처리한 후에 리턴해야 한다.
     *
     * @param key selector에 의해 select된 key.
     */
    override suspend fun handleSelectedKey(key: SelectionKey) {
        try {
            if (key.isValid) {
                if (key.isReadable) {
                    selectionKey.off(SelectionKey.OP_READ) // OP_READ off. wakeup은 필요없다.
                    task.sendEvent(Event.READ)
                } else if (key.isWritable) {
                    selectionKey.off(SelectionKey.OP_WRITE) // OP_WRITE off. wakeup은 필요없다.
                    task.sendEvent(Event.WRITE) // 계속 이어서 진행.
                } else if (key.isConnectable) {
                    selectionKey.off(SelectionKey.OP_CONNECT) // OP_CONNECT off. wakeup은 필요없다.
                    task.sendEvent(Event.FINISH_CONNECT)
                }
            }
        } catch (e: Throwable) {
            task.sendEvent(Event.newErrorEvent(e)) // 다른 thread이므로 event로 보내야 한다.
        }
    }

    /**
     * 내부 coroutine 서비스에 의해 실행되는 event 핸들러이다.
     * CoConnection객체는 event 처리의 동기화 문제를 없애기 위해 순차적으로 event를 처리하는데
     * 그 때 호출되는 내부 핸들러 메소드이다.
     *
     * @param event 내부 이벤트 객체.
     */
    private suspend fun onEvent(event: Event) {
        try {
            when (event.id) {
                EventId.FINISH_CONNECT -> onFinishConnect()
                EventId.CONNECTED -> onConnected()
                EventId.READ -> onRead()
                EventId.WRITE -> onWrite(event)
                EventId.CLOSE -> onClose()
                EventId.CLOSED -> onClosed()
                EventId.ERROR -> onError(event)
                EventId.USER -> onUser(event)
                else -> onError(IllegalStateException())
            }
        } catch (e: Throwable) {
            onError(e)
        }
    }

    private suspend fun onFinishConnect() {
        @Suppress("BlockingMethodInNonBlockingContext")
        channel.finishConnect()

        task.sendEvent(Event.CONNECTED)

        selectionKey.on(SelectionKey.OP_READ) // OP_READ on.
        CoSelector.wakeup()
    }

    private suspend fun onConnected() {
        handlers.onConnectedHandler.invoke(this)
    }

    private suspend fun onRead() {
        val read = internalRead(inBuffer) // read from socket.
        if (read >= 0) {
            try {
                var readableBytes = -1
                while (inBuffer.isReadable && (inBuffer.readableBytes != readableBytes)) {
                    readableBytes = inBuffer.readableBytes
                    inBuffer.mark()
                    var inObj: Any = inBuffer
                    for (codec in handlers.codecChain)
                        inObj = codec.decode(this, inObj)!! // null을 반환하면 즉시 loop 중단된다.
                    handlers.onReadHandler.invoke(this, inObj)
                }
                inBuffer.compact() // marked state is invalidated
            } catch (e: NullPointerException) {
                inBuffer.reset()
            }

            if (channel.isOpen && selectionKey.isValid) { // onReadHandler에서 이미 close되었을 수 있다.
                selectionKey.on(SelectionKey.OP_READ)
                CoSelector.wakeup() // 여기서는 wakeup 필요.
            } else {
                close()
            }
        } else {
            close()
        }
    }

    private suspend fun onWrite(event: Event) {
        if (event.param != null) { // 계속 이어서 진행하는 경우에는 outObj가 null이다.
            var obj: Any = event.param!!
            for (codec in handlers.codecChain.asReversed())
                obj = codec.encode(this, obj)
            if (obj is ReadBuffer) // 최종 obj는 반드시 ReadBuffer이어야 한다.
                outBuffers.add(obj)

            Event.release(event) // param이 not null인 경우 release 해줘야 한다.
        }

        val it = outBuffers.iterator()
        while (it.hasNext()) {
            val buffer = it.next()
            internalWrite(buffer)
            if (buffer.isReadable) // buffer의 내용을 다 write하지 못하고 끝났으면 writable 상태가 아닌 것으로 보고 그만한다.
                break              // 나중에 writable 상태로 돌아오면 다시 시작할 것이다.
            else
                it.remove()
        }

        if (outBuffers.isNotEmpty() && selectionKey.isValid) {
            selectionKey.on(SelectionKey.OP_WRITE)
            CoSelector.wakeup() // 여기서는 wakeup 필요.
        }
    }

    private suspend fun onClose() {
        // close()하기 전에 selector unregister를 먼저 해줘야 한다.
        // selector를 unregister하지 않았도 linux에서는 문제가 없지만 macOS에서는 channel을 close()할 때
        // 가끔 무한 block되는 현상이 있다. 또한 close()를 하면서 selector에 발생하는 OP_READ도 신경쓸 필요가 없어진다.
        // 따라서 반드시 close()하기 전에 먼저 selector를 unregister해주도록 한다.
        CoSelector.unregister(this)
        @Suppress("BlockingMethodInNonBlockingContext")
        channel.close()

        task.sendEvent(Event.CLOSED) // 채널을 닫았으므로 CLOSED를 전송한다.
    }

    private suspend fun onClosed() {
        handlers.onClosedHandler.invoke(this)
        service.stop() // service stop 요청. 큐에 이미 있더라도 이후 event는 모두 무시된다.
    }

    private suspend fun onError(event: Event) {
        onError(event.param as Throwable)
        Event.release(event) // ERROR 이벤트는 항상 param이 null이 아니며 따라서 항상 release되어야 한다.
    }

    private suspend fun onUser(event: Event) {
        handlers.onUserHandler.invoke(this, event.param)
        Event.release(event)
    }

    private suspend fun onIdle() {
        try {
            handlers.onIdleHandler.invoke(this)
        } catch (e: Throwable) {
            onError(e)
        }
    }

    /**
     * 에러가 발생하면 호출된다.
     * onEvent, onIdle에서 exception이 발생하면 이 함수를 호출하고 service를 계속하지만
     * service가 스스로 발생시킨 exception인 경우 이 함수를 호출하고 service는 종료된다.
     * 하지만 현재는 service가 스스로 exception을 발생시킬 일은 없다.
     */
    private suspend fun onError(cause: Throwable) {
        try {
            handlers.onErrorHandler.invoke(this, cause)
        } catch (e: Throwable) {
            e.printStackTrace() // onError에서 발생한 exception을 다시 전파하면 무한 재귀 현상이 나올 수 있다.
        }
    }

    /**
     * 실제로 socket channel에서 data를 읽어들이는 메소드이다.
     * 파라미터로 받은 Buffer 객체로 데이터를 읽어들이고 실제 읽혀진 byte 수를 반환한다.
     *
     * @param buffer 데이터를 읽어들일 Buffer 객체.
     *
     * @return 실제 읽혀진 byte 수.
     */
    private fun internalRead(buffer: Buffer): Int {
        var read: Int
        var total = 0 // 총 읽은 byte 수.

        while (true) {
            val writableBytes = buffer.writableBytes
            read = channel.read(ByteBuffer.wrap(buffer.wArray, buffer.wOffset, writableBytes))
            return if (read >= 0) {
                buffer.wSkip(read)
                total += read
                if (read == writableBytes) // 요청한 양만큼 읽었다면
                    continue // 한 번 더 요청해본다.
                else
                    total
            } else {
                if (total > 0)
                    total // 그 때 까지 읽은 byte 수
                else
                    read // -1
            }
        }
    }

    /**
     * 실제로 socket channel에 data를 write 하는 메소드이다.
     * 파라미터로 받은 ReadBuffer 객체의 내용을 channel에 write하고 실제 write가 된 byte 수를 반환한다.
     *
     * @param readBuffer channel에 write할 데이터를 담은 Buffer 객체.
     *
     * @return 실제 write된 byte 수.
     */
    private fun internalWrite(readBuffer: ReadBuffer) {
        while (readBuffer.isReadable) {
            val lengthToWrite = min(readBuffer.readableBytes, readBuffer.rArray.size - readBuffer.rOffset)
            val written = channel.write(ByteBuffer.wrap(readBuffer.rArray, readBuffer.rOffset, lengthToWrite))
            readBuffer.rSkip(written)
            if (written < lengthToWrite) // 어떤 이유로 요청한 길이만큼을 write하지 못했으면
                break // 일단 그만 한다. 나중에 다시 writable 상태가 오면 다시 시작할 것이다.
        }
    }
}
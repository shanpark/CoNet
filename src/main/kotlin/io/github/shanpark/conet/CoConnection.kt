package io.github.shanpark.conet

import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.util.Event
import io.github.shanpark.conet.util.off
import io.github.shanpark.conet.util.on
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import kotlin.math.min

open class CoConnection(final override val channel: SocketChannel, private val handlers: CoHandlers): CoSelectable {
    companion object {
        const val FINISH_CONNECT = 1 // Event 선언은 0보다 큰 숫자만 가능
        const val CONNECTED = 2
        const val READ = 3
        const val WRITE = 4
        const val CLOSE = 5
        const val CLOSED = 6
    }

    override lateinit var selectionKey: SelectionKey

    private var task = EventLoopCoTask(::onEvent, handlers.idleTimeout, ::onIdle, ::onError)
    protected val service = CoroutineService().start(task)

    private val inBuffer: Buffer = Buffer()
    private val outBuffers = mutableListOf<ReadBuffer>()

    init {
        channel.configureBlocking(false)
    }

    open suspend fun connected() {
        task.sendEvent(Event.CONNECTED)
    }

    suspend fun write(outObj: Any) {
        if (outObj is ReadBuffer)
            task.sendEvent(Event.newWriteEvent(outObj.readSlice(outObj.readableBytes)))
        else
            task.sendEvent(Event.newWriteEvent(outObj))
    }

    suspend fun close() {
        task.sendEvent(Event.CLOSE)
    }

    /**
     * CoSelector의 thread에서만 호출되며 Selector에 OP_READ, OP_WRITE, OP_CONNECT가
     * 발생하면 호출된다. 실행도 역시 CoSelector thread에서 실행된다.
     *
     * 일단 channel에 해당 key가 다시 발생하지 않도록 처리를 즉시해야 하고 가능한 빨리 리턴해야 한다.
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
            task.sendEvent(Event.newErrorEvent(e))
        }
    }

    private suspend fun onEvent(event: Event) {
        try {
            when (event.type) {
                FINISH_CONNECT -> onFinishConnect()
                CONNECTED -> onConnected()
                READ -> onRead()
                WRITE -> onWrite(event)
                CLOSE -> onClose()
                CLOSED -> onClosed()
                Event.ERROR -> onError(event)
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
                do {
                    val readableBytes = inBuffer.readableBytes
                    inBuffer.mark()
                    var inObj: Any = inBuffer
                    for (codec in handlers.codecChain)
                        inObj = codec.encode(this, inObj)!!
                    handlers.onReadHandler.invoke(this, inObj)
                } while (inBuffer.isReadable && (inBuffer.readableBytes != readableBytes))
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
                obj = codec.decode(this, obj)
            if (obj is ReadBuffer) // 최종 obj는 반드시 ReadBuffer이어야 한다.
                outBuffers.add(obj)

            Event.release(event)
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
    private suspend fun onError(e: Throwable) {
        handlers.onErrorHandler.invoke(this, e)
    }

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
package io.github.shanpark.conet

import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.util.Event
import io.github.shanpark.conet.util.log
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import off
import on
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import kotlin.math.min

open class CoConnection(final override val channel: SocketChannel, private val pipeline: CoAction): CoSelectable {

    companion object {
        const val CONNECTED = 1
        const val READ = 2
        const val WRITE = 3
        const val CLOSE = 4
        const val CLOSED = 5
    }

    override lateinit var selectionKey: SelectionKey

    private var task = EventLoopCoTask(::onEvent, 1000, ::onIdle)
    private val service = CoroutineService().start(task)

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
            task.sendEvent(Event.WRITE(outObj.readSlice(outObj.readableBytes)))
        else
            task.sendEvent(Event.WRITE(outObj))
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
        log("CoConnection.handleSelectedKey()")
        if (key.isValid) {
            if (key.isReadable) {
                selectionKey.off(SelectionKey.OP_READ) // OP_READ off. wakeup은 필요없다.
                task.sendEvent(Event.READ)
            } else if (key.isWritable) {
                selectionKey.off(SelectionKey.OP_WRITE) // OP_WRITE off. wakeup은 필요없다.
                task.sendEvent(Event.WRITE) // 계속 이어서 진행.
            } else if (key.isConnectable) {
                @Suppress("BlockingMethodInNonBlockingContext")
                channel.finishConnect()
                selectionKey.off(SelectionKey.OP_CONNECT) // OP_CONNECT off. wakeup은 필요없다.
                task.sendEvent(Event.CONNECTED)
            }
        }
    }

    private suspend fun onEvent(event: Event) {
        when (event.type) {
            CONNECTED -> onConnected()
            READ -> onRead()
            WRITE -> onWrite(event)
            CLOSE -> onClose()
            CLOSED -> onClosed()
        }
    }

    private suspend fun onConnected() {
        pipeline.onConnectedHandler?.invoke(this)
    }

    private suspend fun onRead() {
        val read = internalRead(inBuffer) // read from socket.
        if (read >= 0) {
            try {
                do {
                    val readableBytes = inBuffer.readableBytes
                    inBuffer.mark()
                    var inObj: Any = inBuffer
                    for (codec in pipeline.codecChain)
                        inObj = codec.encode(this, inObj)!!
                    pipeline.onReadHandler?.invoke(this, inObj)
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
                task.sendEvent(Event.CLOSED)
            }
        } else {
            close()
            task.sendEvent(Event.CLOSED)
        }
    }

    private suspend fun onWrite(event: Event) {
        if (event.param != null) { // 계속 이어서 진행하는 경우에는 outObj가 null이다.
            var obj: Any = event.param!!
            for (codec in pipeline.codecChain)
                obj = codec.decode(this, obj)
            if (obj is ReadBuffer) // 최종 obj는 반드시 ReadBuffer이어야 한다.
                outBuffers.add(obj)

            Event.release(event) // param이 null이 아니면 새로 생성한 event이므로 release를 해줘야 garbage가 없다.
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
        @Suppress("BlockingMethodInNonBlockingContext")
        channel.close()
        task.sendEvent(Event.CLOSED)
    }

    private suspend fun onClosed() {
        pipeline.onClosedHandler?.invoke(this)
        service.stop() // service stop 요청. 큐에 이미 있더라도 이후 event는 모두 무시된다.
    }

    private fun onIdle() {
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
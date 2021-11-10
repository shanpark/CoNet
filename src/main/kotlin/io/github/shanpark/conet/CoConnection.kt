package io.github.shanpark.conet

import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.util.log
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

class CoConnection(override val channel: SocketChannel, private val pipeline: CoPipeline): CoSelectable {

    companion object {
        const val CONNECTED = 1
        const val READ = 2
        const val WRITE = 3
        const val CLOSED = 4
    }

    class Event(val type: Int, val param: Any? = null)

    override lateinit var selectionKey: SelectionKey

    private var task: EventLoopCoTask<Event> = EventLoopCoTask(this::onEvent, 1000, this::onIdle)
    private val service: CoroutineService

    private val readBuffer: Buffer = Buffer()

    private var outObjs: MutableList<Any> = mutableListOf()
    private val buffers = mutableListOf<ReadBuffer>()

    init {
        channel.configureBlocking(false)

        service = CoroutineService()
        service.start(task)
    }

//    fun write(outObj: Any) {
//        outObjs.add(outObj)
//    }

    fun close() {
        selectionKey.cancel()
        channel.close()
    }

    /**
     * CoSelector의 thread에서만 호출되며 Selector에 OP_READ, OP_WRITE, OP_CONNECT가
     * 발생하면 호출된다. 실행도 역시 CoSelector thread에서 실행된다.
     *
     * 일단 channel에 해당 key가 다시 발생하지 않도록 처리를 즉시해야 하고 가능한 빨리 리턴해야 한다.
     */
    override suspend fun handleSelectedKey(key: SelectionKey) {
        log("CoConnection.handleSelectedKey()")
        if (key.isReadable) {
            handleReadable()
        } else if (key.isWritable) {
            handleWritable()
        } else if (key.isConnectable) {
            handleConnectable()
        }
    }

    private suspend fun handleReadable() {
        val buffer = Buffer() // TODO 항상 새로 할당되는데 이렇게하면 garbage가 넘치게 됨.
        val read = internalRead(buffer) // Key에서 OP_READ를 없애주기 위해서는 여기서 모두 읽어들어야 한다.
        if (read >= 0)
            task.sendEvent(Event(READ, buffer))
        else {
            close()
            task.sendEvent(Event(CLOSED))
        }
    }

    private suspend fun handleWritable() {
    }

    private suspend fun handleConnectable() {
        task.sendEvent(Event(CONNECTED))
    }

    private suspend fun onEvent(event: Event) {
        when (event.type) {
            CONNECTED -> onConnected()
            READ -> onRead(event.param as Buffer)
            WRITE -> {}
            CLOSED -> onClosed()
        }
    }

    private suspend fun onConnected() {
        log("onConnected()")
    }

    /**
     * 이 메소드가 호출되는 시점은 이미 readBuffer에 값이 들어와 있는 상태이다.
     */
    private suspend fun onRead(buffer: Buffer) {
//        readBuffer.write(buffer)

        while (true) {
            val readableBytes = readBuffer.readableBytes

            var inObj: Any? = readBuffer
            for (handler in pipeline.onReadHandlers) {
                inObj = handler.invoke(this, inObj!!)
                if (inObj == null)
                    break
            }

            if (!readBuffer.isReadable || (readBuffer.readableBytes == readableBytes))
                break // all or nothing used.
        }

        readBuffer.compact() // marked state is invalidated
    }

    private fun onWrite() {

    }

    private fun onClosed() {
        log("onClosed()")
    }

    private fun onIdle() {
    }

//    private suspend fun handleConnect() {
//        pipeline.onConnectedHandlers.forEach { it.invoke(this) }
//    }
//
//    private suspend fun handleRead() {
//        val isClosed = internalRead()
//
//        var obj: Any? = buffer
//        while (true) {
//            val readableBytes = buffer.readableBytes
//
//            for (handler in pipeline.onReadHandlers) {
//                obj = handler.invoke(this, obj!!)
//                if (obj == null)
//                    break
//            }
//
//            if (!buffer.isReadable || (readableBytes == buffer.readableBytes)) {
//                break
//            }
//        }
//        buffer.compact() // marked states are invalidated.
//
//        if (isClosed)
//            pipeline.onClosedHandlers.forEach { it.invoke(this) }
//    }
//
//    private suspend fun handleWrite() {
//        if (outObjs.isNotEmpty()) {
//            for (outObj in outObjs) {
//                var obj: Any = outObj
//                for (handler in pipeline.onWriteHandlers) {
//                    obj = handler.invoke(this, obj)
//                }
//                if (obj is ReadBuffer)
//                    buffers.add(obj)
//                // obj는 반드시 ReadBuffer
//            }
//
//            val it = buffers.iterator()
//            while (it.hasNext()) {
//                val buffer = it.next()
//                internalWrite(buffer)
//                if (buffer.isReadable) // buffer의 내용을 다 write하지 못하고 끝났으면 writable 상태가 아닌 것으로 보고 그만한다.
//                    break              // 나중에 writable 상태로 돌아오면 다시 시작할 것이다.
//                else
//                    it.remove()
//            }
//        }
//    }

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

//    private fun internalWrite(readBuffer: ReadBuffer): Boolean {
//        return try {
//            while (readBuffer.isReadable) {
//                val lengthToWrite = readBuffer.rArray.size - readBuffer.rOffset
//                val written = channel.write(ByteBuffer.wrap(readBuffer.rArray, readBuffer.rOffset, lengthToWrite))
//                readBuffer.rSkip(written)
//                if (written < lengthToWrite) // 어떤 이유로 요청한 길이만큼을 write하지 못했으면
//                    break // 일단 그만 한다. 나중에 다시 writable 상태가 오면 다시 시작할 것이다.
//            }
//            true
//        } catch (e: ClosedChannelException) {
//            false
//        }
//    }
}
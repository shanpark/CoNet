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

    override lateinit var selectionKey: SelectionKey

    private var task = EventLoopCoTask(this::onEvent, 1000, this::onIdle)
    private val service = CoroutineService().start(task)

    private val readBuffer: Buffer = Buffer()

    private var outObjs: MutableList<Any> = mutableListOf()
    private val buffers = mutableListOf<ReadBuffer>()

    init {
        channel.configureBlocking(false)
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
        selectionKey.interestOps(selectionKey.interestOps() and SelectionKey.OP_READ.inv()) // OP_READ off. wakeup은 필요없다.
        task.sendEvent(READ)
    }

    private suspend fun handleWritable() {
    }

    private suspend fun handleConnectable() {
        task.sendEvent(CONNECTED)
    }

    private suspend fun onEvent(event: Int) {
        when (event) {
            CONNECTED -> onConnected()
            READ -> onRead()
            WRITE -> {}
            CLOSED -> onClosed()
        }
    }

    private suspend fun onConnected() {
        log("onConnected()")
    }

    private suspend fun onRead() {
        val read = internalRead(readBuffer) // read from socket.
        if (read >= 0) {
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

            selectionKey.interestOps(selectionKey.interestOps() or SelectionKey.OP_READ) // OP_READ on
            CoSelector.wakeup() // 여기서는 wakeup 필요.
        } else {
            close()
            task.sendEvent(CLOSED)
        }
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
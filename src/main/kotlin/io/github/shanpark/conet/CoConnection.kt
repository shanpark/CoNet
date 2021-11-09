package io.github.shanpark.conet

import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.services.coroutine.EventLoopCoTask
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

class CoConnection(override val channel: SocketChannel, private val pipeline: CoPipeline): CoSelectable {
    private var task: EventLoopCoTask<Int> = EventLoopCoTask(this::onSelect, 1000, this::onIdle)
    private var buffer: Buffer = Buffer(1024)
    private var outObjs: MutableList<Any> = mutableListOf()
    private val buffers = mutableListOf<ReadBuffer>()

    init {
        channel.configureBlocking(false)
    }

    fun write(outObj: Any) {
        outObjs.add(outObj)
    }

    fun close() {
        channel.close()
    }

    /**
     * CoSelector의 thread에서만 호출되며 Selector에 OP_READ, OP_WRITE, OP_CONNECT가
     * 발생하면 호출된다.
     */
    override suspend fun selected(readyOps: Int) {
        task.sendEvent(readyOps)
    }

    private suspend fun onSelect(keys: Int) {
        if (keys.or(SelectionKey.OP_READ) > 0) {
            handleRead()
        } else if (keys.or(SelectionKey.OP_WRITE) > 0) {
            handleWrite()
        } else if (keys.or(SelectionKey.OP_CONNECT) > 0) {
            handleConnect()
        }
    }

    private fun onIdle() {
    }

    private suspend fun handleConnect() {
        pipeline.onConnectedHandlers.forEach { it.invoke(this) }
    }

    private suspend fun handleRead() {
        val isClosed = internalRead()

        var obj: Any? = buffer
        while (true) {
            val readableBytes = buffer.readableBytes

            for (handler in pipeline.onReadHandlers) {
                obj = handler.invoke(this, obj!!)
                if (obj == null)
                    break
            }

            if (!buffer.isReadable || (readableBytes == buffer.readableBytes)) {
                break
            }
        }
        buffer.compact() // marked states are invalidated.

        if (isClosed)
            pipeline.onClosedHandlers.forEach { it.invoke(this) }
    }

    private suspend fun handleWrite() {
        if (outObjs.isNotEmpty()) {
            for (outObj in outObjs) {
                var obj: Any = outObj
                for (handler in pipeline.onWriteHandlers) {
                    obj = handler.invoke(this, obj)
                }
                if (obj is ReadBuffer)
                    buffers.add(obj)
                // obj는 반드시 ReadBuffer
            }

            val it = buffers.iterator()
            while (it.hasNext()) {
                val buffer = it.next()
                internalWrite(buffer)
                if (buffer.isReadable) // buffer의 내용을 다 write하지 못하고 끝났으면 writable 상태가 아닌 것으로 보고 그만한다.
                    break              // 나중에 writable 상태로 돌아오면 다시 시작할 것이다.
                else
                    it.remove()
            }
        }
    }

    private fun internalRead(): Boolean {
        var read: Int

        while (true) {
            val writableBytes = buffer.writableBytes
            read = channel.read(ByteBuffer.wrap(buffer.wArray, buffer.wOffset, writableBytes))
            return if (read < 0)
                false
            else if (read == writableBytes) // 가득 읽었으면 더 읽어본다.
                continue
            else
                true
        }
    }

    private fun internalWrite(readBuffer: ReadBuffer): Boolean {
        return try {
            while (readBuffer.isReadable) {
                val lengthToWrite = readBuffer.rArray.size - readBuffer.rOffset
                val written = channel.write(ByteBuffer.wrap(readBuffer.rArray, readBuffer.rOffset, lengthToWrite))
                readBuffer.rSkip(written)
                if (written < lengthToWrite) // 어떤 이유로 요청한 길이만큼을 write하지 못했으면
                    break // 일단 그만 한다. 나중에 다시 writable 상태가 오면 다시 시작할 것이다.
            }
            true
        } catch (e: ClosedChannelException) {
            false
        }
    }
}
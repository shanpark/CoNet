package io.github.shanpark.conet

import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.services.coroutine.EventLoopCoTask
import io.ktor.network.sockets.*

class ConnectionContext(private val socket: Socket, val pipeline: EventPipeline) {
    lateinit var writeTask: EventLoopCoTask<ReadBuffer>

    suspend fun write(outObj: Any) {
        var obj: Any? = outObj
        val it = pipeline.onWriteHandlers.iterator()
        while (it.hasNext()) {
            obj = it.next().invoke(this, obj!!)
            if (obj == null)
                break
        }

        if (obj is ReadBuffer)
            writeTask.sendEvent(obj)
    }

    fun close() {
        socket.dispose()
    }
}
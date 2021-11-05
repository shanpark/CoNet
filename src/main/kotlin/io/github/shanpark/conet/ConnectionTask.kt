package io.github.shanpark.conet

import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.services.coroutine.CoTask
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import io.github.shanpark.services.signal.Signal
import io.ktor.network.sockets.*
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionTask(private val socket: Socket, private val pipeline: EventPipeline): CoTask {
    private var context = ConnectionContext(socket, pipeline)
    private val inputChannel = socket.openReadChannel()
    private val outputChannel = socket.openWriteChannel(autoFlush = true)
    private val readBuffer = Buffer(1024)

    override suspend fun init() {
        val task = EventLoopCoTask<ReadBuffer>({
            while (it.isReadable) {
                it.rSkip(outputChannel.writeAvailable(it.rArray, it.rOffset, it.rArray.size - it.rOffset))
            }
        })
        context.writeTask = task
        val service = CoroutineService()
        service.start(task)

        pipeline.onConnectedHandlers.forEach { it.invoke(context) }
    }

    override suspend fun run(stopSignal: Signal) {
        try {
            while (true) {
                val read = withTimeoutOrNull(1000L) {
                    inputChannel.readAvailable(readBuffer.wArray, readBuffer.wOffset, readBuffer.writableBytes)
                }
                if (stopSignal.isSignalled() || read == -1)
                    break
                if (read == null)
                    continue

                while (true) {
                    val remainBytes = readBuffer.readableBytes

                    var acc: Any? = readBuffer
                    val it = pipeline.onReadHandlers.iterator()
                    while (it.hasNext()) {
                        acc = it.next().invoke(context, acc!!)
                        if (acc == null)
                            break
                    }

                    if (readBuffer.readableBytes == 0 || readBuffer.readableBytes == remainBytes) // all or none
                        break
                }
            }
        } catch (e: Throwable) {
            pipeline.onErrorHandlers.forEach { it.invoke(context, e) }
        }
    }

    override suspend fun uninit() {
        socket.dispose()
        pipeline.onClosedHandlers.forEach { it.invoke(context) }
    }
}
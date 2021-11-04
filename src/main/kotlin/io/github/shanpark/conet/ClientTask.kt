package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.CoTask
import io.github.shanpark.services.signal.Signal
import io.ktor.network.selector.*
import io.ktor.network.sockets.*

class ClientTask(private val client: Socket, private val pipeline: EventPipeline): CoTask {
    private var context = Context(client.openReadChannel(), client.openWriteChannel())

    override suspend fun init() {

        pipeline.onConnectedHandlers.foreach { it.invoke(context) }

    }

    override suspend fun run(stopSignal: Signal) {
    }

    override suspend fun uninit() {
        client.dispose()
        pipeline.onClosedHandlers.foreach { it.invoke(context) }
    }
}
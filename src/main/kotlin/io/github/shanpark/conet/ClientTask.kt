package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.CoTask
import io.github.shanpark.services.signal.Signal
import io.ktor.network.sockets.*
import java.net.InetSocketAddress

class ClientTask(private val socketBuilder: TcpSocketBuilder, private val pipeline: EventPipeline, private val address: InetSocketAddress): CoTask {
    private lateinit var connectionTask: ConnectionTask

    override suspend fun init() {
        val socket = socketBuilder.connect(address)

        connectionTask = ConnectionTask(socket, pipeline)
        connectionTask.init()
    }

    override suspend fun run(stopSignal: Signal) {
        connectionTask.run(stopSignal)
    }

    override suspend fun uninit() {
        connectionTask.uninit()
    }
}
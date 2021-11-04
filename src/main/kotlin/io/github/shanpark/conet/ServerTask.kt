package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.CoTask
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.signal.Signal
import io.ktor.network.sockets.*
import java.net.InetSocketAddress

class ServerTask(private val socketBuilder: TcpSocketBuilder, private val address: InetSocketAddress): CoTask {

    private lateinit var serverSocket: ServerSocket

    override suspend fun init() {
        serverSocket = socketBuilder.bind(address)
    }

    override suspend fun run(stopSignal: Signal) {
        while (!stopSignal.isSignalled()) {
            val socket = serverSocket.accept()
            val clientTask = ClientTask(socket, pipeline)
            val service = CoroutineService()
            service.start(clientTask)
        }
    }

    override suspend fun uninit() {
        serverSocket.dispose()
    }
}
package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.CoroutineService
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress

class Server {
    val dispatcher = Dispatchers.IO
    val selector: ActorSelectorManager = ActorSelectorManager(dispatcher)
    val tcpSocketBuilder: TcpSocketBuilder = aSocket(selector).tcp()

    fun start(address: InetSocketAddress) {
        val serverTask = ServerTask(tcpSocketBuilder)
        val service = CoroutineService()
        service.start(serverTask)
    }
}
package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.CoroutineService
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress

class Server(private val pipeline: EventPipeline) {
    val dispatcher = Dispatchers.IO
    val selector: ActorSelectorManager = ActorSelectorManager(dispatcher)
    val tcpSocketBuilder: TcpSocketBuilder = aSocket(selector).tcp()

    private var service: CoroutineService? = null

    fun start(address: InetSocketAddress): Server {
        val serverTask = ServerTask(tcpSocketBuilder, pipeline, address)
        service = CoroutineService()
        service!!.start(serverTask)
        return this
    }

    fun stop() {
        service?.stop()
    }

    fun await(millis: Long = 0) {
        service?.await(millis)
    }
}
package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.CoroutineService
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress

class Client(private val pipeline: EventPipeline) {
    val dispatcher = Dispatchers.IO
    val selector: ActorSelectorManager = ActorSelectorManager(dispatcher)
    val tcpSocketBuilder: TcpSocketBuilder = aSocket(selector).tcp()

    private var service: CoroutineService? = null

    fun connect(address: InetSocketAddress): Client {
        val connectionTask = ClientTask(tcpSocketBuilder, pipeline, address)
        service = CoroutineService()
        service!!.start(connectionTask)
        return this
    }

    fun stop() {
        service?.stop()
    }

    fun await(millis: Long = 0) { // TODO Services에도 default value 지정해주자.
        service?.await(millis)
    }
}
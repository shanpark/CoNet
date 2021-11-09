package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class CoServer(private val pipeline: CoPipeline): CoSelectable {
    override val channel: ServerSocketChannel = ServerSocketChannel.open()

    private lateinit var task: EventLoopCoTask<SocketChannel>
    private lateinit var service: CoroutineService

    init {
        channel.configureBlocking(false)
        task = EventLoopCoTask(this::onAccepted, 1000L, this::onIdle)
    }

    fun start(address: InetSocketAddress): CoServer {
        channel.bind(address)
        CoSelector.register(this, SelectionKey.OP_ACCEPT)

        service = CoroutineService()
        service.start(task)
        return this
    }

    fun stop() {
        channel.close()
        service.stop()
    }

    override suspend fun selected(readyOps: Int) {
        if (readyOps.or(SelectionKey.OP_ACCEPT) > 0) {
            val socketChannel = channel.accept()
            task.sendEvent(socketChannel)
        }
    }

    private fun onAccepted(socketChannel: SocketChannel) {
        CoSelector.register(CoConnection(socketChannel, pipeline), SelectionKey.OP_READ or SelectionKey.OP_WRITE or SelectionKey.OP_CONNECT)
    }

    private fun onIdle() {

    }
}
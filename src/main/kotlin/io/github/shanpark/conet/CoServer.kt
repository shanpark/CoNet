package io.github.shanpark.conet

import io.github.shanpark.conet.util.log
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class CoServer(private val pipeline: CoPipeline): CoSelectable {
    override var channel: ServerSocketChannel = ServerSocketChannel.open()
    override lateinit var selectionKey: SelectionKey

    private lateinit var task: EventLoopCoTask<SocketChannel>
    private lateinit var service: CoroutineService

    init {
        channel.configureBlocking(false)
    }

    fun start(address: InetSocketAddress): CoServer {
        if (!service.isRunning()) {
            channel.bind(address)
            CoSelector.register(this, SelectionKey.OP_ACCEPT)

            task = EventLoopCoTask(this::onAccepted, 1000L, this::onIdle)
            service = CoroutineService()
            service.start(task)
        }
        return this
    }

    override suspend fun handleSelectedKey(key: SelectionKey) {
        log("CoServer.handleSelectedKey()")
        if (key.isAcceptable) {
            @Suppress("BlockingMethodInNonBlockingContext")
            task.sendEvent(channel.accept())
        }
    }

    private suspend fun onAccepted(socketChannel: SocketChannel) {
        log("CoServer.onAccepted()")
        val connection = CoConnection(socketChannel, pipeline)
        CoSelector.register(connection, SelectionKey.OP_READ)
        connection.start() // connection start.
    }

    private fun onIdle() {
    }
}
package io.github.shanpark.conet

import io.github.shanpark.conet.util.log
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class CoServer(private val pipeline: CoAction): CoSelectable {
    override var channel: ServerSocketChannel = ServerSocketChannel.open()
    override lateinit var selectionKey: SelectionKey

    private var task: EventLoopCoTask<SocketChannel> = EventLoopCoTask(this::onAccepted)
    private var service = CoroutineService().start(task)

    init {
        channel.configureBlocking(false)
    }

    fun start(address: InetSocketAddress): CoServer {
        if (!channel.isRegistered) {
            CoSelector.register(this, SelectionKey.OP_ACCEPT)
            channel.bind(address)
        }
        return this
    }

    fun stop() {
        service.stop()
    }

    fun await() {
        service.await()
    }

    override suspend fun handleSelectedKey(key: SelectionKey) {
        log("CoServer.handleSelectedKey()")
        if (key.isValid && key.isAcceptable) {
            @Suppress("BlockingMethodInNonBlockingContext")
            task.sendEvent(channel.accept())
        }
    }

    private suspend fun onAccepted(socketChannel: SocketChannel) {
        log("CoServer.onAccepted()")
        val connection = CoConnection(socketChannel, pipeline)
        connection.connected() // connection start.
        CoSelector.register(connection, SelectionKey.OP_READ)
    }
}
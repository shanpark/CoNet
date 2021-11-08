package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.EventLoopCoTask
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

class CoServer: CoSelectable {
    override val channel: ServerSocketChannel = ServerSocketChannel.open()

    private lateinit var task: EventLoopCoTask<Int>

    init {
        channel.configureBlocking(false)
    }

    fun bind(address: InetSocketAddress) {
        channel.bind(address)
        CoSelector.register(this, SelectionKey.OP_ACCEPT)

        task = EventLoopCoTask(this::onAccept, 1000, this::onIdle)
    }

    override suspend fun selected(readyOps: Int) {
        task.sendEvent(readyOps)
    }

    private fun onAccept(key: Int) {
        if (key.or(SelectionKey.OP_ACCEPT) > 0) {
            val socketChannel = channel.accept()
            CoSelector.register(CoConnection(socketChannel), SelectionKey.OP_READ or SelectionKey.OP_WRITE or SelectionKey.OP_CONNECT)
        }
    }

    private fun onIdle() {
    }
}
package io.github.shanpark.conet

import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

class CoClient(handlers: CoHandlers): CoConnection(SocketChannel.open(), handlers) {
    override suspend fun connected() {
        throw UnsupportedOperationException("connected() is for CoServer.")
    }

    fun connect(address: InetSocketAddress): CoClient {
        if (!channel.isRegistered) {
            CoSelector.register(this, SelectionKey.OP_CONNECT or SelectionKey.OP_READ)
            channel.connect(address)
        }
        return this
    }

    fun stop(): CoClient {
        service.stop()
        return this
    }

    fun await(millis: Long = 0) {
        service.await(millis)
    }

    fun isRunning(): Boolean {
        return service.isRunning()
    }
}
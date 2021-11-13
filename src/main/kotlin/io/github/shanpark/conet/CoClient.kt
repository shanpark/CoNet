package io.github.shanpark.conet

import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

class CoClient(pipeline: CoPipeline): CoConnection(SocketChannel.open(), pipeline) {
    override suspend fun connected() {
        throw UnsupportedOperationException("connected() is for CoServer.")
    }

    fun connect(address: InetSocketAddress): CoClient {
        CoSelector.register(this, SelectionKey.OP_CONNECT or SelectionKey.OP_READ)
        channel.connect(address)

        return this
    }
}
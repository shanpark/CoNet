package io.github.shanpark.conet

import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

class CoClient(handlers: CoHandlers): CoConnection(SocketChannel.open(), handlers) {
    override suspend fun connected() {
        throw UnsupportedOperationException("connected() is for CoServer.")
    }

    fun connect(address: InetSocketAddress): CoClient {
        if (!channel.isRegistered) {
            channel.connect(address)
            CoSelector.register(this, SelectionKey.OP_CONNECT or SelectionKey.OP_READ) // 등록은 connect() 후에 해줘야 한다.
        }
        return this
    }

    fun stop(): CoClient {
        runBlocking { close() }
        return this
    }

    fun await(millis: Long = 0) {
        service.await(millis)
    }

    fun isRunning(): Boolean {
        return service.isRunning()
    }
}
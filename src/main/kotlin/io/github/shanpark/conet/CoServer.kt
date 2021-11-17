package io.github.shanpark.conet

import io.github.shanpark.conet.util.Event
import io.github.shanpark.conet.util.log
import io.github.shanpark.services.coroutine.CoroutineService
import io.github.shanpark.services.coroutine.EventLoopCoTask
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class CoServer(private val handlerFactory: () -> CoHandlers): CoSelectable {
    companion object {
        const val STOP = 0
        const val ACCEPT = 1 // Event 선언은 0보다 큰 숫자만 가능
    }

    override var channel: ServerSocketChannel = ServerSocketChannel.open()
    override lateinit var selectionKey: SelectionKey

    private var task: EventLoopCoTask<Event> = EventLoopCoTask(this::onEvent)
    private var service = CoroutineService().start(task)

    init {
        channel.configureBlocking(false)
    }

    fun start(address: InetSocketAddress): CoServer {
        if (!channel.isRegistered) {
            channel.bind(address)
            CoSelector.register(this, SelectionKey.OP_ACCEPT) // register는 bind() 후에 해줘야 한다.
        }
        return this
    }

    fun stop(): CoServer {
        runBlocking {
            task.sendEvent(Event.newStop())
        }
        return this
    }

    fun await(millis: Long = 0) {
        service.await(millis)
    }

    /**
     * CoServer의 key에는 OP_ACCEPT만 발생한다.
     *
     * 이 메소드에서는 어떤 exception도 발생시켜서는 안된다.
     */
    override suspend fun handleSelectedKey(key: SelectionKey) {
        try {
            if (key.isValid && key.isAcceptable) {
                @Suppress("BlockingMethodInNonBlockingContext")
                task.sendEvent(Event.newAccept(channel.accept()))
            }
        } catch (e: Exception) {
            task.sendEvent(Event.newError(e))
        }
    }

    private suspend fun onEvent(event: Event) {
        when (event.type) {
            ACCEPT -> onAccept(event)
            STOP -> onStop()
            Event.ERROR -> onError(event)
        }
    }

    private suspend fun onAccept(event: Event) {
        val connection = CoConnection(event.param as SocketChannel, handlerFactory.invoke())
        connection.connected() // connection start.
        CoSelector.register(connection, SelectionKey.OP_READ)
    }

    private fun onStop() {
        service.stop()
        channel.close()
    }

    private fun onError(event: Event) {
        log("CoServer.onError()")
        // server channel의 accept()가 에러나는 경우이다.
        (event.param as Throwable).printStackTrace()
        Event.release(event) // ERROR 이벤트는 param이 항상 null이 아니면 따라서 항상 release되어야 한다.
    }
}
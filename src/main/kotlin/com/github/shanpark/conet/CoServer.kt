package com.github.shanpark.conet

import com.github.shanpark.conet.util.Event
import com.github.shanpark.conet.util.EventId
import com.github.shanpark.services.coroutine.CoroutineService
import com.github.shanpark.services.coroutine.EventLoopCoTask
import kotlinx.coroutines.runBlocking
import java.net.SocketAddress
import java.net.SocketOption
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * TCP 소켓 서버를 구현한 클래스.
 * TCP 접속 요청을 accept하여 새로운 CoConnection객체를 생성해준다.
 * 내부적으로 accept()를 수행하기 위한 coroutine 서비스를 생성하여 실행하며
 * stop()이 호출될 때 까지 계속된다. stop()이 호출되어 최종적으로 내부 서비스가 종료된 이후에는
 * 다시 이 객체는 재사용할 수 없다.
 *
 * @param handlersFactory CoHandlers 객체를 생성하여 반환하는 factory 메소드. 새로운 CoConnection 객체를 생성할 때 마다
 *                        호출하여 새로운 CoConnection 객체가 사용하도록 한다.
 */
class CoServer(private val handlersFactory: () -> CoHandlers<CoTcp>): CoSelectable {
    override var channel: ServerSocketChannel = ServerSocketChannel.open()
    override lateinit var selectionKey: SelectionKey

    private var task: EventLoopCoTask<Event> = EventLoopCoTask(this::onEvent)
    private var service = CoroutineService().start(task)

    init {
        channel.configureBlocking(false)
    }

    /**
     * 파라미터로 전달된 주소에 binding하여 listen / accept 작업을 시작한다.
     * 이미 시작된 상태에서는 아무것도 하지 않는다.
     *
     * @param address binding할 주소 객체.
     *
     * @return 이 객체 반환
     */
    fun start(address: SocketAddress): CoServer {
        if (!channel.isRegistered) {
            channel.bind(address)
            CoSelector.register(this, SelectionKey.OP_ACCEPT) // register는 bind() 후에 해줘야 한다.
        }
        return this
    }

    /**
     * 실행 중단을 요청한다.
     * 비동기로 수행되며 최종적으로 내부 서비스가 종료되어야 완전히 종료된 것으로 볼 수 있다.
     * await() 메소드를 통해서 최종 종료시까지 대기할 수 있다.
     *
     * @return 이 객체 반환
     */
    fun stop(): CoServer {
        runBlocking {
            task.sendEvent(Event.STOP)
        }
        return this
    }

    /**
     * 전달된 옵션을 내부적으로 사용하는 ServerSocketChannel 객체에 전달 적용한다.
     *
     * @param name The socket option
     * @param value The value of the socket option. A value of null may be a valid value for some socket options.
     *
     * @return 이 객체 반환
     */
    fun <T> setOption(name: SocketOption<T>, value: T): CoServer {
        channel.setOption(name, value)
        return this
    }

    /**
     * 서비스가 종료될 때 까지 대기한다.
     * 파라미터로 지정된 시간(ms)이 지나면 서비스가 종료되지 않았더라도 함수가 반환된다.
     * default 값인 0이 지정되면 서비스가 종료될 때 까지 무한 대기한다.
     */
    fun await(millis: Long = 0) {
        service.await(millis)
    }

    /**
     * 이벤트 처리를 위해서 생성된 내부 coroutine 서비스가 실행 중인지 여부를 반환한다.
     *
     * @return coroutine 서비스가 실행중이면 true, 아니면 false.
     */
    fun isRunning(): Boolean {
        return service.isRunning()
    }

    /**
     * CoServer의 key에는 OP_ACCEPT만 발생한다.
     *
     * 필요한 작업을 빠르게 처리하고 가능한 빨리 리턴하는 것이 좋다.
     * 내부적으로 발생하는 모든 exception은 전파되어서는 안되고 반드시 처리한 후에 리턴해야 한다.
     */
    override suspend fun handleSelectedKey(key: SelectionKey) {
        try {
            if (key.isValid && key.isAcceptable) {
                @Suppress("BlockingMethodInNonBlockingContext")
                task.sendEvent(Event.newEvent(EventId.ACCEPT, channel.accept()))
            }
        } catch (e: Exception) {
            task.sendEvent(Event.newErrorEvent(e))
        }
    }

    private suspend fun onEvent(event: Event) {
        try {
            when (event.id) {
                EventId.ACCEPT -> onAccept(event)
                EventId.STOP -> onStop()
                EventId.ERROR -> onError(event)
                else -> onError(IllegalStateException())
            }
        } catch (e: Throwable) {
            onError(e)
        }
    }

    private suspend fun onAccept(event: Event) {
        val connection = CoTcp(event.param as SocketChannel, handlersFactory.invoke())
        connection.connected() // connection start.
        CoSelector.register(connection, SelectionKey.OP_READ)
        Event.release(event) // ACCEPT 이벤트는 param이 항상 null이 아니다. 따라서 항상 release되어야 한다.
    }

    private fun onStop() {
        service.stop()

        CoSelector.unregister(this)
        channel.close()
    }

    private fun onError(event: Event) {
        onError(event.param as Throwable)
        Event.release(event) // ERROR 이벤트는 param이 항상 null이 아니다. 따라서 항상 release되어야 한다.
    }

    private fun onError(cause: Throwable) {
        try {
            cause.printStackTrace() // server 자체는 사용자 handler가 없다. 따라서 현재는 그냥 stack trace 만 출력한다.
        } catch (e: Throwable) {
            e.printStackTrace() // onError에서 excepion이 발생한다면 무한 재귀가 될 수 있기 때문에 여기서 처리한다.
        }
    }
}
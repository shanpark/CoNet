package com.github.shanpark.conet

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * TCP 소켓 클라이언트 객체를 구현하는 클래스.
 * CoClient 객체가 생성되면 내부적으로 Event 처리를 위한 coroutine이 생성되며 서버에 연결하고 작업을 수행한 후
 * 접속이 끊어지고 나면 관련 coroutine도 종료된다. 이후 CoClient 객체는 재사용할 수 없다.
 *
 * @param handlers connection에서 발생하는 이벤트 처리를 구현한 CoHandlers 객체.
 */
class CoClient(handlers: CoHandlers): CoConnection(SocketChannel.open(), handlers) {

    /**
     * CoClient 객체로 하여금 서버에 연결 요청을 하도록 하는 메소드이다.
     * 비동기로 연결 요청을 수행하며 연결이 이루어지고 난 다음에는 지정된 CoCHandlers 객체의 onConnected 핸들러가 호출된다.
     * 이미 연결을 요청한 상태에서는 아무 것도 하지 않는다.
     * 연결 후 모든 작업을 마치고 접속이 끊어진 후에는 다시 접속할 수 없으므로 호출해서는 안된다.
     *
     * @param address 연결을 요청할 remote 주소 객체
     *
     * @return 자기 자신(CoClient 객체)을 반환.
     */
    fun connect(address: InetSocketAddress): CoClient {
        if (!channel.isRegistered) {
            channel.connect(address)
            CoSelector.register(this, SelectionKey.OP_CONNECT) // 등록은 connect() 후에 해줘야 한다.
        }
        return this
    }

    /**
     * CoClient 객체로 하여금 서버와 연결을 끊고 작업을 종료하도록 요청하는 메소드이다.
     * 이 후 접속이 종료되어 onDisconnect 핸들러가 호출된 이후에 이 객체는 더 이상 사용할 수 없다.
     * 내부적으로 생성된 coroutine 서비스도 모두 종료될 것이다.
     *
     * @return 자기 자신(CoClient 객체)을 반환.
     */
    fun stop(): CoClient {
        runBlocking { close() }
        return this
    }

    /**
     * CoClient의 접속이 종료되어 내부 coroutine 서비스가 종료될 때 까지 대기한다.
     * 파라미터로 지정된 시간(ms)이 지나면 서비스가 종료되지 않았더라도 함수가 반환된다.
     * default 값인 0이 지정되면 서비스가 종료될 때 까지 무한 대기한다.
     *
     * @param millis 서비스 종료를 기다리는 최대 대기 시간.
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
}
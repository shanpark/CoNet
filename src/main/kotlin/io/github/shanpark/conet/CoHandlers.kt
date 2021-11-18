package io.github.shanpark.conet

typealias OnConnected = suspend (conn: CoConnection) -> Unit
typealias OnRead = suspend (conn: CoConnection, inObj: Any) -> Unit
typealias OnClosed = suspend (conn: CoConnection) -> Unit
typealias OnError = suspend (conn: CoConnection, e: Throwable) -> Unit
typealias OnIdle = suspend (conn: CoConnection) -> Unit

/**
 * 각 connection에서 발생하는 이벤트를 처리하는 클래스.
 * 모든 connection은 자신만의 handler 객체를 가지고 있으며 business logic의 구현이
 * 이루어져야 하는 곳이다.
 *
 * 이 클래스를 상속받아서 CoHandlers 객체를 구현할 수 도 있고 직접 CoHandlers 객체를 생성하여
 * onXxxHandler 속성에 함수 객체를 지정하여 customizing하는 것도 가능하다.
 */
open class CoHandlers {
    var idleTimeout: Long = Long.MAX_VALUE

    var onConnectedHandler: OnConnected = ::onConnected
    var onReadHandler: OnRead = ::onRead
    var onClosedHandler: OnClosed = ::onClosed
    var onErrorHandler: OnError = ::onError
    var onIdleHandler: OnIdle = ::onIdle

    var codecChain: MutableList<CoCodec> = mutableListOf()

    /**
     * 접속이 이루어지면 가장 먼저 호출되는 handler 함수.
     *
     * @param conn CoConnection 객체.
     */
    open suspend fun onConnected(conn: CoConnection) {}

    /**
     * 읽을 수 있는 data가 도착했을 때 호출되는 handler 함수.
     *
     * codecChain이 구성되지 않은 경우에는 inObj 파라미터는 ReadBuffer 객체가 전달된다.
     * 도착한 데이터를 읽어서 사용하지 않으면 buffer의 내용은 계속 누적된 채로 전달되므로 적절히 읽어서 사용해야 한다.
     *
     * codecChain이 구성된 경우 codecChain을 거져서 최종적으로 생성된 객체가 전달된다.
     * codecChain을 거쳐서 생성된 객체는 다음 onRead() 호출에서는 받을 수 없으므로 객체를 받으면 적절히 처리해야 한다.
     *
     * @param conn CoConnection 객체.
     * @param inObj codecChain이 구성된 경우에는 codecChain을 거쳐서 생성된 객체. codecChain이 구성되지 않은 경우 도착한 데이터를
     *              담고 있는 ReadBuffer 객체.
     */
    open suspend fun onRead(conn: CoConnection, inObj: Any) {}

    /**
     * 접속이 종료되면 마지막으로 호출되는 handler 함수.
     * 이미 접속이 종료가 되어 관련 socket, channel등이 close가 된 후에 마지막으로 호출된다.
     *
     * @param conn CoConnection 객체.
     */
    open suspend fun onClosed(conn: CoConnection) {}

    /**
     * connection의 handler에서 에러가 발생하면 호출된다.
     *
     * @param conn CoConnection 객체.
     * @param cause 에러를 발생시킨 exception 객체.
     */
    open suspend fun onError(conn: CoConnection, cause: Throwable) {}

    /**
     * idleTimeout 속성에 지정된 시간(ms) 동안 어떤 handler도 호출되지 않으면 호출되는 handler 함수.
     * read, write, error 등의 이벤트 없이 idleTimeout 시간이 지나면 호출된다.
     *
     * @param conn CoConnection 객체.
     */
    open suspend fun onIdle(conn: CoConnection) {}
}
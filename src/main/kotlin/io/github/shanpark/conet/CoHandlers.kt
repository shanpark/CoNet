package io.github.shanpark.conet

typealias OnConnected = suspend (conn: CoConnection) -> Unit
typealias OnRead = suspend (conn: CoConnection, inObj: Any) -> Unit
typealias OnClosed = suspend (conn: CoConnection) -> Unit
typealias OnError = suspend (conn: CoConnection, e: Throwable) -> Unit
typealias OnIdle = suspend (conn: CoConnection) -> Unit

open class CoHandlers {
    var idleTimeout: Long = Long.MAX_VALUE

    var onConnectedHandler: OnConnected = ::onConnected
    var onReadHandler: OnRead = ::onRead
    var onClosedHandler: OnClosed = ::onClosed
    var onErrorHandler: OnError = ::onError
    var onIdleHandler: OnIdle = ::onIdle

    var codecChain: MutableList<CoCodec> = mutableListOf()

    open suspend fun onConnected(conn: CoConnection) {
    }

    open suspend fun onRead(conn: CoConnection, inObj: Any) {
    }

    open suspend fun onClosed(conn: CoConnection) {
    }

    open suspend fun onError(conn: CoConnection, e: Throwable) {
    }

    open suspend fun onIdle(conn: CoConnection) {
    }
}
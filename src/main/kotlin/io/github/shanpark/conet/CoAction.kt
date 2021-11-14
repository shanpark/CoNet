package io.github.shanpark.conet

typealias OnConnected = suspend (conn: CoConnection) -> Unit
typealias OnRead = suspend (conn: CoConnection, inObj: Any) -> Unit
typealias OnClosed = suspend (conn: CoConnection) -> Unit
typealias OnError = suspend (conn: CoConnection, e: Throwable) -> Unit
typealias OnIdle = suspend (conn: CoConnection) -> Unit

open class CoAction {
    var idleTimeout: Long = Long.MAX_VALUE

    var onConnectedHandler: OnConnected? = null
    var onReadHandler: OnRead? = null
    var onClosedHandler: OnClosed? = null
    var onErrorHandler: OnError? = null
    var onIdleHandler: OnIdle? = null

    var codecChain: MutableList<CoCodec> = mutableListOf()
}
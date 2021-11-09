package io.github.shanpark.conet

typealias OnConnected = suspend (conn: CoConnection) -> Unit
typealias OnRead = suspend (conn: CoConnection, inObj: Any) -> Any?
typealias OnWrite = suspend (conn: CoConnection, outObj: Any) -> Any
typealias OnClosed = suspend (conn: CoConnection) -> Unit
typealias OnError = suspend (conn: CoConnection, e: Throwable) -> Unit

class CoPipeline {

    var onConnectedHandlers = mutableListOf<OnConnected>()
    var onReadHandlers = mutableListOf<OnRead>()
    var onWriteHandlers = mutableListOf<OnWrite>()
    var onClosedHandlers = mutableListOf<OnClosed>()
    var onErrorHandlers = mutableListOf<OnError>()

    fun addOnConnected(handler: OnConnected) {
        onConnectedHandlers.add(handler)
    }

    fun addOnRead(handler: OnRead) {
        onReadHandlers.add(handler)
    }

    fun addOnWrite(handler: OnWrite) {
        onWriteHandlers.add(handler)
    }

    fun addOnClosed(handler: OnClosed) {
        onClosedHandlers.add(handler)
    }

    fun addOnError(handler: OnError) {
        onErrorHandlers.add(handler)
    }
}
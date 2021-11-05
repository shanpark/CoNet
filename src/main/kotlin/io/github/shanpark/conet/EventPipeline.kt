package io.github.shanpark.conet

typealias OnConnectedHandler = suspend (context: ConnectionContext) -> Unit
typealias OnReadHandler = suspend (context: ConnectionContext, inObj: Any) -> Any?
typealias OnWriteHandler = suspend (context: ConnectionContext, outObj: Any) -> Any?
typealias OnClosedHandler = suspend (context: ConnectionContext) -> Unit
typealias OnErrorHandler = suspend (context: ConnectionContext, cause: Throwable) -> Unit

class EventPipeline {

    val onConnectedHandlers: MutableList<OnConnectedHandler> = mutableListOf()
    val onReadHandlers: MutableList<OnReadHandler> = mutableListOf()
    val onWriteHandlers: MutableList<OnWriteHandler> = mutableListOf()
    val onClosedHandlers: MutableList<OnClosedHandler> = mutableListOf()
    val onErrorHandlers: MutableList<OnErrorHandler> = mutableListOf()

    fun addOnConnected(handler: OnConnectedHandler) {
        onConnectedHandlers.add(handler)
    }

    fun addOnRead(handler: OnReadHandler) {
        onReadHandlers.add(handler)
    }

    fun addOnWrite(handler: OnWriteHandler) {
        onWriteHandlers.add(handler)
    }

    fun addOnClosed(handler: OnClosedHandler) {
        onClosedHandlers.add(handler)
    }

    fun addOnError(handler: OnErrorHandler) {
        onErrorHandlers.add(handler)
    }
}
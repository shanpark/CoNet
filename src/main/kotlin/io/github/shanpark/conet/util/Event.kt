package io.github.shanpark.conet.util

import io.github.shanpark.conet.CoConnection
import io.github.shanpark.conet.CoServer
import io.github.shanpark.services.util.EventPool

class Event(var type: Int, var param: Any? = null) {
    companion object {
        val FINISH_CONNECT = Event(CoConnection.FINISH_CONNECT)
        val CONNECTED = Event(CoConnection.CONNECTED)
        val READ = Event(CoConnection.READ)
        val WRITE = Event(CoConnection.WRITE)
        val CLOSE = Event(CoConnection.CLOSE)
        val CLOSED = Event(CoConnection.CLOSED)

        const val STOP = 0
        const val ERROR = -1

        private val _STOP = Event(STOP)

        @Suppress("FunctionName")
        fun newAcceptEvent(param: Any): Event {
            val event = eventPool.get()
            event.type = CoServer.ACCEPT
            event.param = param
            return event
        }

        @Suppress("FunctionName")
        fun newWriteEvent(param: Any): Event {
            val event = eventPool.get()
            event.type = CoConnection.WRITE
            event.param = param
            return event
        }

        @Suppress("FunctionName")
        fun newStopEvent(): Event {
            return _STOP
        }

        @Suppress("FunctionName")
        fun newErrorEvent(param: Any): Event {
            val event = eventPool.get()
            event.type = ERROR
            event.param = param
            return event
        }

        fun release(event: Event) {
            eventPool.ret(event)
        }

        private val eventPool: EventPool<Event> = EventPool({ Event(ERROR) }, 100)
    }
}

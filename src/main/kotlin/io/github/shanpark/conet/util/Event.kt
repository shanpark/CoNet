package io.github.shanpark.conet.util

import io.github.shanpark.conet.CoConnection
import io.github.shanpark.services.util.EventPool

class Event(var type: Int, var param: Any? = null) {
    companion object {
        val CONNECTED = Event(CoConnection.CONNECTED)
        val READ = Event(CoConnection.READ)
        val WRITE = Event(CoConnection.WRITE)
        val CLOSE = Event(CoConnection.CLOSE)
        val CLOSED = Event(CoConnection.CLOSED)

        @Suppress("FunctionName")
        fun WRITE(param: Any): Event {
            val event = eventPool.get()
            event.type = CoConnection.WRITE
            event.param = param
            return event
        }

        fun release(event: Event) {
            eventPool.ret(event)
        }

        private val eventPool: EventPool<Event> = EventPool({ Event(CoConnection.WRITE) }, 100)
    }
}

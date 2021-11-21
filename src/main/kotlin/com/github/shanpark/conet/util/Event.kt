package com.github.shanpark.conet.util

import com.github.shanpark.services.coroutine.CoPool

enum class EventId(val value: Int) {
    ACCEPT(1),
    FINISH_CONNECT(2),
    CONNECTED(3),
    READ(4),
    WRITE(5),
    SEND(6),
    CLOSE(7),
    CLOSED(8),
    STOP(9),

    USER(1000),

    ERROR(-1);
}

/**
 * CoNet framework 내부적으로 사용되는 Event 객체 클래스.
 */
internal class Event(var id: EventId, var param: Any? = null) {
    companion object {
        // parameter가 필요없는 event는 미리 만들어 놓고 singleton 형태로 재활용한다.
        val FINISH_CONNECT = Event(EventId.FINISH_CONNECT)
        val CONNECTED = Event(EventId.CONNECTED)
        val READ = Event(EventId.READ)
        val WRITE = Event(EventId.WRITE) // write 이벤트는 param이 있을 때도 있고 없을 때도 있다. 없을 때만 이걸 사용한다.
        val CLOSE = Event(EventId.CLOSE)
        val CLOSED = Event(EventId.CLOSED)
        val STOP = Event(EventId.STOP)

        fun newEvent(id: EventId, param: Any): Event {
            val event = eventPool.get()
            event.id = id
            event.param = param
            return event
        }

        fun newUserEvent(param: Any?): Event {
            val event = eventPool.get()
            event.id = EventId.USER
            event.param = param
            return event
        }

        fun newErrorEvent(param: Any): Event {
            val event = eventPool.get()
            event.id = EventId.ERROR
            event.param = param
            return event
        }

        fun release(event: Event) {
            eventPool.ret(event)
        }

        private val eventPool = CoPool({ Event(EventId.ERROR) }, 1000)
    }
}

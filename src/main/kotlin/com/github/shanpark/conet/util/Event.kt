package com.github.shanpark.conet.util

import com.github.shanpark.conet.CoConnection
import com.github.shanpark.conet.CoServer
import com.github.shanpark.services.util.EventPool

/**
 * CoNet framework 내부적으로 사용되는 Event 객체 클래스.
 */
class Event(var type: Int, var param: Any? = null) {
    companion object {
        // parameter가 필요없는 event는 미리 만들어 놓고 singleton 형태로 재활용한다.
        val FINISH_CONNECT = Event(CoConnection.FINISH_CONNECT)
        val CONNECTED = Event(CoConnection.CONNECTED)
        val READ = Event(CoConnection.READ)
        val WRITE = Event(CoConnection.WRITE) // write 이벤트는 param이 있을 때도 있고 없을 때도 있다. 없을 때만 이걸 사용한다.
        val CLOSE = Event(CoConnection.CLOSE)
        val CLOSED = Event(CoConnection.CLOSED)
        val STOP = Event(CoServer.STOP)

        const val ERROR = -1

        fun newEvent(type: Int, param: Any): Event {
            val event = eventPool.get()
            event.type = type
            event.param = param
            return event
        }

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

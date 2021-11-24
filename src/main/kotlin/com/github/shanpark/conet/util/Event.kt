package com.github.shanpark.conet.util

import com.github.shanpark.services.coroutine.CoPool

/**
 * CoNet 프레임워크 내에서 사용되는 모든 Event의 ID이다.
 */
internal enum class EventId(val value: Int) {
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
 *
 * @param id Event 객체의 식별자
 * @param param Event에 부가적으로 전송하는 parameter 객체. 그런 데이터가 없는 경우 null.
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

        /**
         * Event pool에 여분의 Event 객체가 있으면 꺼내서 반환한다.
         * pool이 비어있다면 새로운 Event 객체가 생성될 것이다.
         *
         * @param id 반환되는 Event객체의 id.
         * @param param Event 객체와 함께 전송할 부가 정보 객체. 부가 정보가 없는 경우는 newEvent()로 새로 할당하지 않고 singleton 행태로
         *              선언된 객체가 따로 있기 때문에 이 함수를 사용하지 않는다. 따라서 param은 null을 전달할 수 없다.
         *
         * @return 새로 생성되었거나 pool에서 꺼내어 초기화된 Event 객체.
         */
        fun newEvent(id: EventId, param: Any): Event {
            val event = eventPool.get()
            event.id = id
            event.param = param
            return event
        }

        /**
         * id가 EventId.USER인 Event 객체를 생성한다.
         * newEvent()로 생성한 것과 같지만 편의상 제공되는 함수이다.
         *
         * @param param Event 객체와 함께 전송할 부가 정보 객체.
         *
         * @return 새로 생성되었거나 pool에서 꺼내어 초기화된 Event 객체.
         */
        fun newUserEvent(param: Any?): Event {
            val event = eventPool.get()
            event.id = EventId.USER
            event.param = param
            return event
        }


        /**
         * id가 EventId.ERROR인 Event 객체를 생성한다.
         * newEvent()로 생성한 것과 같지만 편의상 제공되는 함수이다.
         *
         * @param param Event 객체와 함께 전송할 부가 정보 객체.
         *
         * @return 새로 생성되었거나 pool에서 꺼내어 초기화된 Event 객체.
         */
        fun newErrorEvent(param: Any): Event {
            val event = eventPool.get()
            event.id = EventId.ERROR
            event.param = param
            return event
        }

        /**
         * Event 객체를 pool에 반환한다.
         *
         * @param event pool에 반환할 event 객체.
         */
        fun release(event: Event) {
            eventPool.ret(event)
        }

        /**
         * Event 객체가 수시로 필요하기 때문에 garbage가 양산되는 걸 막기 위해서 Event 객체 pool을 제공한다.
         * 최대 1000개 까지의 Event 객체를 보관하고 있다가 필요할 때 하나씩 꺼내 쓸 수 있도록 한다.
         */
        private val eventPool = CoPool({ Event(EventId.ERROR) }, 1000)
    }
}

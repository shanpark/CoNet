package com.github.shanpark.conet

import kotlinx.coroutines.runBlocking
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicReference

/**
 * 모든 CoSelectable 객체들이 공동으로 사용하는 단 하나의 Selector 객체를 관리하고
 * Selector 객체에 발생하는 selected key들을 등록된 각 CoSelectable 객체로 전달한다.
 *
 * selected key는 handleSelectedKey()를 호출하여 전달하며 CoSelectable 객체는 Selector가 바로 다음
 * selection을 수행할 수 있도록 가능한 빠르게 key를 처리하고 리턴해야 한다.
 *
 * 생성된 Selector 객체를 최초 access할 때 selection thread는 생성되어 시작된다. 그리고나서 등록된 키가
 * 하나도 없을 때 thread는 자동으로 종료된다.
 */
object CoSelector {

    private class SelectorWrapper {
        val selector: Selector by lazy { startSelector() }
        val registerRequestList: MutableList<RegisterRequest> = mutableListOf()

        fun register(selectable: CoSelectable, interestKeys: Int) {
            synchronized(registerRequestList) {
                registerRequestList.add(RegisterRequest(selectable, interestKeys))
            }
            selector.wakeup()
        }

        fun unregister(selectable: CoSelectable) {
            selectable.channel.keyFor(selector)?.cancel()
        }

        fun wakeup() {
            selector.wakeup()
        }

        /**
         * selector를 생성하여 반환하고, 실제 select() 작업을 수행하는 스레드도 생성한다.
         * 최초에 selector를 access할 때 한 번만 호출된다.
         *
         * thread 내부에서는 exception 처리를 전혀 하지 않으므로 특정 handler 등에서 발생하는 exception은
         * 반드시 handler내부에서 알아서 처리해야 한다. 만약 처리되지 않은 excepion이 전파된다면 thread는
         * 즉시 중단되고 selection이 동작하지 않으므로 모든 socket 작업 또한 중단된다.
         *
         * 여기서 생성된 selector는 모든 CoSelectable 객체가 함께 사용한다. 마지막 키가 등록해제되면
         * thread는 종료된다. 하지만 selectorWrapper에는 새로운 SelectorWrapper 객체를 생성해서 지정해놓기 때문에
         * 이후에 selector를 다시 access하는 경우가 발생하면 lazy binding에 의하여 다시 thread가 생성되어
         * 실행될 것이다.
         */
        private fun startSelector(): Selector {
            Thread {
                runBlocking {
                    do {
                        selector.select()

                        val it = selector.selectedKeys().iterator()
                        while (it.hasNext()) {
                            val key = it.next()
                            it.remove()
                            (key.attachment() as CoSelectable).handleSelectedKey(key)
                        }

                        if (registerRequestList.isNotEmpty()) {
                            synchronized(registerRequestList) {
                                for (request in registerRequestList)
                                    internalRegister(request)
                                registerRequestList.clear()
                            }
                        }
                    } while(selector.keys().isNotEmpty())

                    selectorWrapper.set(SelectorWrapper()) // replace with new one. and this thread exit.
                }
            }.start()

            return Selector.open()
        }

        private fun internalRegister(request: RegisterRequest) {
            request.selectable.selectionKey = request.selectable.channel.register(selector, request.interestOpts, request.selectable)
        }
    }

    private class RegisterRequest(val selectable: CoSelectable, val interestOpts: Int)

    private var selectorWrapper = AtomicReference(SelectorWrapper())

    /**
     * CoSelectable 객체를 관심 ops와 함께 CoSelector에 등록한다. 등록이 완료되면 CoSelectable객체의
     * selectionKey 속성에는 등록 시 생성된 SelectionKey 객체가 설정된다.
     *
     * 관심 ops의 이벤트가 발생하면 CoSelectable 객체의 handleSelectedKey()가 호출된다.
     *
     * connect()나 bind() 같이 selector에 operation을 발생시키는 함수들은 register()를 호출하기 전에
     * 먼저 non-blocking 모드로 호출한 후에 register()를 호출하여 등록해주도록 한다.
     */
    fun register(selectable: CoSelectable, interestKeys: Int) {
        selectorWrapper.get().register(selectable, interestKeys)
    }

    /**
     * 등록된 CoSelectable 객체를 등록 해제 시킨다.
     */
    fun unregister(selectable: CoSelectable) {
        selectorWrapper.get().unregister(selectable)
        selectorWrapper.get().wakeup()
    }

    /**
     * selector의 select() 작업을 멈추고 즉시 리턴하도록 한다.
     * 새로운 CoSelectable 객체를 등록하려고 할 때나 등록된 CoSelectable 객체가 자신의 selectionKey에 설졍을
     * 변경하고나서 Selector가 이를 인식하도록 하기 위해 주로 호출한다.
     */
    fun wakeup() {
        selectorWrapper.get().wakeup()
    }
}
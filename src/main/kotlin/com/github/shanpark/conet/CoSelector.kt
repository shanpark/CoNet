package com.github.shanpark.conet

import kotlinx.coroutines.runBlocking
import java.nio.channels.Selector

/**
 * 모든 CoSelectable 객체들이 공동으로 사용하는 단 하나의 Selector 객체를 관리하고
 * Selector 객체에 발생하는 selected key들을 등록된 각 CoSelectable 객체로 전달한다.
 *
 * selected key는 handleSelectedKey()를 호출하여 전달하며 CoSelectable 객체는 Selector가 바로 다음
 * selection을 수행할 수 있도록 가능한 빠르게 key를 처리하고 리턴해야 한다.
 */
object CoSelector {
    private class RegisterRequest(val selectable: CoSelectable, val interestOpts: Int)

    private val registerRequestList: MutableList<RegisterRequest> = mutableListOf()
    private val selector: Selector by lazy {
        startSelector()
    }

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
        synchronized(registerRequestList) {
            registerRequestList.add(RegisterRequest(selectable, interestKeys))
        }
        selector.wakeup()
    }

    /**
     * 등록된 CoSelectable 객체를 등록 해제 시킨다.
     */
    fun unregister(selectable: CoSelectable) {
        selectable.channel.keyFor(selector)?.cancel()
    }

    /**
     * selector의 select() 작업을 멈추고 즉시 리턴하도록 한다.
     * 새로운 CoSelectable 객체를 등록하려고 할 때나 등록된 CoSelectable 객체가 자신의 selectionKey에 설졍을
     * 변경하고나서 Selector가 이를 인식하도록 하기 위해 주로 호출한다.
     */
    fun wakeup() {
        selector.wakeup()
    }

    /**
     * selector를 생성하여 반환하고, 실제 select() 작업을 수행하는 스레드도 생성한다.
     * 최초에 selector를 access할 때 한 번만 호출된다.
     *
     * 여기서 생성된 selector는 모든 CoSelectable 객체가 함께 사용하고 생성된 스레드도
     * process의 종료 시 까지 계속 수행된다.
     */
    private fun startSelector(): Selector {
        val selector = Selector.open()

        Thread {
            runBlocking {
                while (true) {
                    CoSelector.selector.select()

                    val it = CoSelector.selector.selectedKeys().iterator()
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
                }
            }
        }.start()

        return selector
    }

    private fun internalRegister(request: RegisterRequest) {
        request.selectable.selectionKey = request.selectable.channel.register(selector, request.interestOpts, request.selectable)
    }
}
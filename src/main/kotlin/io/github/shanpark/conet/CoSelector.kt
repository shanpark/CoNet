package io.github.shanpark.conet

import io.github.shanpark.conet.util.log
import kotlinx.coroutines.runBlocking
import java.nio.channels.Selector

object CoSelector {
    class RegisterRequest(val selectable: CoSelectable, val interestOpts: Int)

    private val registerRequestList: MutableList<RegisterRequest> = mutableListOf()
    private val selector: Selector by lazy {
        startSelector()
    }

    /**
     * CoSelectable 객체를 관심 key와 함께 CoSelector에 등록한다.
     * 관심 key 이벤트가 발생하면 CoSelectable 객체의 selected()가 호출될 것이다.
     */
    fun register(selectable: CoSelectable, interestKeys: Int) {
        synchronized(registerRequestList) {
            registerRequestList.add(RegisterRequest(selectable, interestKeys))
        }
        selector.wakeup()
    }

    fun wakeup() {
        selector.wakeup()
    }

    private fun startSelector(): Selector {
        val selector = Selector.open()

        Thread {
            runBlocking {
                while (true) {
                    log("CoSelector.selector.select()")
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
package io.github.shanpark.conet

import kotlinx.coroutines.runBlocking
import java.nio.channels.Selector

object CoSelector {
    private val selector: Selector by lazy {
        startSelector()
    }

    /**
     * CoSelectable 객체를 관심 key와 함께 CoSelector에 등록한다.
     * 관심 key 이벤트가 발생하면 CoSelectable 객체의 selected()가 호출될 것이다.
     */
    fun register(selectable: CoSelectable, interestKeys: Int) {
        selectable.channel.register(selector, interestKeys, selectable)
    }

    private fun startSelector(): Selector {
        val selector = Selector.open()

        Thread {
            runBlocking {
                while (true) {
                    selector.select()
                    val selectedKeys = selector.selectedKeys()
                    for (key in selectedKeys) {
                        if (key.isValid) {
                            val selectable = (key.attachment() as CoSelectable)
                            val keyObject = selectable.handleKey(key.readyOps()) // on thread
                            selectable.handleKeyObject(keyObject) // on coroutine
                        }
                    }
                    selectedKeys.clear()
                }
            }
        }.start()

        return selector
    }
}
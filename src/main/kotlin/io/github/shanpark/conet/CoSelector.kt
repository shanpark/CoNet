package io.github.shanpark.conet

import kotlinx.coroutines.runBlocking
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

object CoSelector {
    private val selector: Selector by lazy {
        startSelector()
    }

    fun register(selectable: CoSelectable, interestKeys: Int) {
        selectable.channel.register(selector, interestKeys, selectable)
    }

    private fun startSelector(): Selector {
        val selector = Selector.open()

        Thread {
            runBlocking {
                selector.select()
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next() as SelectionKey
                    keys.remove()

                    @Suppress("UNCHECKED_CAST")
                    if (key.isValid)
                        (key.attachment() as CoSelectable).selected(key.readyOps())
                }
            }
        }

        return selector
    }
}
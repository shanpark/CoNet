package io.github.shanpark.conet

import io.github.shanpark.services.coroutine.EventLoopCoTask
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey

class CoConnection(override val channel: SelectableChannel): CoSelectable {
    private var task: EventLoopCoTask<Int>

    init {
        channel.configureBlocking(false)

        task = EventLoopCoTask(this::onEvent, 1000, this::onIdle)
    }

    override suspend fun selected(readyOps: Int) {

    }

    private fun onEvent(keys: Int) {
        if (keys.or(SelectionKey.OP_READ) > 0) {

        } else if (keys.or(SelectionKey.OP_WRITE) > 0) {

        } else if (keys.or(SelectionKey.OP_CONNECT) > 0) {

        }
    }

    private fun onIdle() {

    }
}
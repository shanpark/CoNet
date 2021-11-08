package io.github.shanpark.conet

import java.nio.channels.SelectableChannel

interface CoSelectable {
    val channel: SelectableChannel

    suspend fun selected(readyOps: Int)
}
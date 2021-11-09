package io.github.shanpark.conet

import java.nio.channels.SelectableChannel

interface CoSelectable {
    val channel: SelectableChannel

    fun handleKey(readyOps: Int): Any?
    suspend fun handleKeyObject(obj: Any?)
}
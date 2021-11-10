package io.github.shanpark.conet

import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey

interface CoSelectable {
    val channel: SelectableChannel
    var selectionKey: SelectionKey

    suspend fun handleSelectedKey(key: SelectionKey)
}
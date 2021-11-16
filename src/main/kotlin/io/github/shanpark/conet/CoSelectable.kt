package io.github.shanpark.conet

import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey

interface CoSelectable {
    val channel: SelectableChannel
    var selectionKey: SelectionKey

    /**
     * select()중인 key에 이벤트가 발생하면 호출된다.
     *
     * 이 메소드는 어떤 exception도 발생시켜서는 안되며 내부적으로 모두 처리후에 리턴하도록 해야 한다.
     */
    suspend fun handleSelectedKey(key: SelectionKey)
}
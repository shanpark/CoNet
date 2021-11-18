package io.github.shanpark.conet

import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey

/**
 * CoSelector에 등록하기 위해 구현해야 하는 인터페이스.
 */
interface CoSelectable {
    /**
     * selector에 등록될 channel 객체.
     */
    val channel: SelectableChannel

    /**
     * selector에 등록 시 반환된 SelectionKey를 참조.
     */
    var selectionKey: SelectionKey

    /**
     * select()중인 key에 이벤트가 발생하면 호출된다.
     *
     * 이 메소드는 어떤 exception도 발생시켜서는 안되며 내부적으로 모두 처리한 후에 리턴하도록 해야 한다.
     * 만약 처리를 하지 못하면 전체 CoNet 시스템이 중단된다.
     */
    suspend fun handleSelectedKey(key: SelectionKey)
}
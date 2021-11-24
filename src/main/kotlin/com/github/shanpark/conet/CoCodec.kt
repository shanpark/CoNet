package com.github.shanpark.conet

/**
 * CoHandlers 객체의 codecChain을 구성하는 codec 객체를 위한 interface.
 * CoCodec 객체는 inbound, outbound 흐름에 따라 데이터를 encoding, decoding 작업을 수행한다.
 *
 * - TCP용 코덱인 경우
 *   inbound 시에는 ReadBuffer에서 특정 타입의 객체로 변환(encode)을 수행하여 CoHandlers의 onRead() 핸들러로
 *   전달하고 outbound 시에는 write() 메소드로 전달된 객체를 serialize하여 최종적으로 ReadBuffer 객체를 생성해서
 *   반환한다.
 * - UDP용 코덱인 경우
 *   inbound 시에는 DatagramPacket에서 특정 타입의 객체로 변환(encode)을 수행하여 CoHandlers의 onRead() 핸들러로
 *   전달하고 outbound 시에는 write() 메소드로 전달된 객체를 serialize하여 최종적으로 DatagramPacket 객체를 생성해서
 *   반환한다. UDP는 TCP와 달리 flow가 끝나고 나면 모든 사용되지 않은 data는 모두 버려지기 때문에 전달된 DatagramPacket을
 *   모두 사용해야 한다.
 *
 * inbound 시에는 codecChain의 앞에 추가된 codec부터 차례대로 거쳐서 마지막 codec이 반환하는 객체가 CoHandler의
 * onRead() 핸들러로 전달되고 outbound 시에는 반대로 codecChain의 마지막 codec부터 앞쪽으로 차례대로 거쳐서
 * 맨 앞의 codec의 decode() 메소드가 반환하는 객체(ReadBuffer or DatagramPacket)의 내용이 remote로 전송된다.
 *
 * 항상 connection의 coroutine 서비스에서 호출되므로 모든 메소드는 suspend 함수이다.
 */
interface CoCodec<HANDLERS> {
    /**
     * inbound flow 에서 호출되는 메소드로서 이전 codec이 반환한 객체를 받아서 다음 codec으로 전달한
     * 객체를 생성 반환한다.
     * codec chain의 맨 앞에 있는 코덱은 inObj로 ReadBuffer를 받게 되어있으며
     * 마지막 코덱은 최종적으로 onRead() 메소드에 전달할 객체를 생성하여 반환하여야 한다.
     * 최종 객체를 생성하기에 데이터가 부족하거나 여러가지 이유로 더 이상 codec chain의 encoding을
     * 진행할 필요가 없을 때에는 null을 반환한다.
     *
     * @param handlers CoConnection 객체.
     * @param inObj 이전 codec 객체가 반환한 객체. 맨 앞에 있는 codec은 ReadBuffer를 받는다.
     *
     * @return 다음 codec으로 전달할 객체. 마지막 codec은 CoHandlers의 onRead()로 전달할 객체를 반환해야 한다.
     *         다음 codec으로의 진행을 하지 않도록 중단하려면 null을 반환한다.
     */
    suspend fun encode(handlers: HANDLERS, inObj: Any): Any?

    /**
     * outbound flow 에서 호출되는 메소드로서 이전 codec이 반환한 객체를 받아서 다음 codec으로 전달한
     * 객체를 생성 반환한다. outbound 시에는 codec chain의 역순으로 호출되므로 물리적으로 뒤에 위치한 codec이
     * 이전 codec이 된다.
     * codec chain의 맨 처음 호출되는 코덱은 outObj로 write() 메소드를 호출할 때 지정한 객체를 받게 되어있으며
     * 마지막으로 호출되는 코덱은 최종적으로 ReadBuffer 객체를 생성하여 반환하여야 한다.
     *
     * @param handlers CoConnection 객체.
     * @param outObj 이전 codec 객체가 반환한 객체. 맨 마지막으로 호출되는 codec은 ReadBuffer 객체를 반환해야 한다.
     *
     * @return 다음 codec으로 전달할 객체. 마지막 codec은 ReadBuffer 또는 DatagramPacket 객체를 반환해야 한다.
     *         encode()와 달리 null을 반환할 수 없으며 반드시 다음 codec으로 전달할 깨체를 반환해야 한다.
     */
    suspend fun decode(handlers: HANDLERS, outObj: Any): Any
}

/**
 * TcpCodec 코덱은
 * - inbound - codec chain의 첫 번째 codec의 encode함수는 항상 ReadBuffer 객체를 받는다.
 * - outbound - codec chain의 첫 번째 codec의 decode 함수는 반드시 ReadBuffer 객체를 반환해야 한다.
 */
typealias TcpCodec = CoCodec<TcpHandlers>

/**
 * UdpCodec 코덱은
 * - inbound - codec chain의 첫 번째 codec의 encode함수는 항상 DatagramPacket 객체를 받는다.
 * - outbound - codec chain의 첫 번째 codec의 decode 함수는 반드시 DatagramPacket 객체를 반환해야 한다.
 */
typealias UdpCodec = CoCodec<UdpHandlers>

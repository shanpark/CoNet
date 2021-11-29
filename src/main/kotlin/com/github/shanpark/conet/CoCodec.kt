package com.github.shanpark.conet

/**
 * CoHandlers 객체의 codecChain을 구성하는 codec 객체를 위한 interface.
 * CoCodec 객체는 inbound, outbound 흐름에 따라 데이터를 encoding, decoding 작업을 수행한다.
 *
 * - TCP용 코덱인 경우
 *   inbound 시에는 ReadBuffer에서 특정 타입의 객체로 변환(decode)을 수행하여 CoHandlers의 onRead() 핸들러로
 *   전달하고 outbound 시에는 write() 메소드로 전달된 객체를 serialize하여 최종적으로 ReadBuffer 객체를 생성해서
 *   반환한다.
 * - UDP용 코덱인 경우
 *   inbound 시에는 DatagramPacket에서 특정 타입의 객체로 변환(decode)을 수행하여 CoHandlers의 onRead() 핸들러로
 *   전달하고 outbound 시에는 write() 메소드로 전달된 객체를 serialize하여 최종적으로 DatagramPacket 객체를 생성해서
 *   반환한다. UDP는 TCP와 달리 flow가 끝나고 나면 모든 사용되지 않은 data는 모두 버려지기 때문에 전달된 DatagramPacket을
 *   모두 사용해야 한다.
 *
 * inbound 시에는 codecChain의 앞에 추가된 codec부터 차례대로 거쳐서 마지막 codec이 반환하는 객체가 CoHandler의
 * onRead() 핸들러로 전달되고 outbound 시에는 반대로 codecChain의 마지막 codec부터 앞쪽으로 차례대로 거쳐서
 * 맨 앞의 codec의 encode() 메소드가 반환하는 객체(ReadBuffer or DatagramPacket)의 내용이 remote로 전송된다.
 *
 * 맨 마지막 codec까지 거쳐서 최종적으롤 null이 아닌 객체를 생성해내면 남은 데이터로 (실제 데이터가 있건 없건) 계속해서
 * 다시 처음부터 codec chain을 다시 시도하므로 모든 codec은 적절히 다음 codec으로 넘겨줄 데이터가 없는 시점을 판단하여
 * null을 반환해야 다시 소켓에서 데이터를 읽어오는 작업으로 진행할 수 있다.
 *
 * 항상 connection의 coroutine 서비스에서 호출되므로 모든 메소드는 suspend 함수이다.
 */
interface CoCodec<CONN> {
    /**
     * 연결이 이루어진 직후에 호출된다.
     * 특별히 연결 직후 코덱에서 초기화 해야 하는 작업이 있다면 여기서 해준다.
     * UDP 처럼 연결이 없다면 호출되지 않을 수 있다.
     *
     * codec의 onConnected()는 CoHandlers의 onConnected() 직후에 호출된다.
     *
     * default로 아무 것도 하지 않는 메소드가 구현되어 있으므로 반드시 제공해야하는 것은 아니다.
     *
     * @param conn CoTcp 또는 CoUdp 객체.
     */
    suspend fun onConnected(conn: CONN) {}

    /**
     * inbound flow 에서 호출되는 메소드로서 이전 codec이 반환한 객체를 받아서 다음 codec으로 전달할 객체를 생성 반환한다.
     * codec chain의 맨 앞에 있는 코덱은 inObj로 ReadBuffer 또는 DatagramPacket을 받게 되어있으며
     * 마지막 코덱은 최종적으로 onRead() 메소드에 전달할 객체를 생성하여 반환하여야 한다.
     * 최종 객체를 생성하기에 데이터가 부족하거나 여러가지 이유로 더 이상 codec chain의 encoding을
     * 진행할 필요가 없을 때에는 null을 반환한다.
     *
     * codec chain의 마지막까지 거쳐서 null이 아닌 객체가 반환되면 계속해서 다시 codec chain을 통한 decoding을 시도하므로
     * decode() 메소드는 반드시 현재 codec에서의 중단 조건을 명확하게 구현하여 중단 조건이 발생하면 반드시 null을 반환하는 로직이
     * 포함되어야 한다. 그렇지 못한 경우 무한 loop에 빠지게 된다.
     *
     * @param conn CoTcp 또는 CoUdp 객체.
     * @param inObj 이전 codec 객체가 반환한 객체. 맨 앞에 있는 codec은 ReadBuffer를 받는다.
     *
     * @return 다음 codec으로 전달할 객체. 마지막 codec은 CoHandlers의 onRead()로 전달할 객체를 반환해야 한다.
     *         다음 codec으로의 진행을 하지 않도록 중단하려면 null을 반환한다.
     */
    suspend fun decode(conn: CONN, inObj: Any): Any?

    /**
     * outbound flow 에서 호출되는 메소드로서 이전 codec이 반환한 객체를 받아서 다음 codec으로 전달한
     * 객체를 생성 반환한다. outbound 시에는 codec chain의 역순으로 호출되므로 물리적으로 뒤에 위치한 codec이
     * 이전 codec이 된다.
     * codec chain의 맨 처음 호출되는 코덱은 outObj로 write() 메소드를 호출할 때 지정한 객체를 받게 되어있으며
     * 마지막으로 호출되는 코덱은 최종적으로 ReadBuffer 객체를 생성하여 반환하여야 한다.
     *
     * @param conn CoTcp 또는 CoUdp 객체.
     * @param outObj 이전 codec 객체가 반환한 객체. 맨 마지막으로 호출되는 codec은 ReadBuffer 객체를 반환해야 한다.
     *
     * @return 다음 codec으로 전달할 객체. 마지막 codec은 ReadBuffer 또는 DatagramPacket 객체를 반환해야 한다.
     *         encode()와 달리 null을 반환할 수 없으며 반드시 다음 codec으로 전달할 객체를 반환해야 한다.
     */
    suspend fun encode(conn: CONN, outObj: Any): Any

    /**
     * 접속 종료를 요청받은 경우에 호출된다.
     * 연결을 끊기 전에 코덱에서 정리되어야 하는 작업이 있다면 여기서 해준다. 연결을 끊기 전에 peer와 shutdown 작업을
     * 필요로하는 경우 유용하다. (TLS 연결 같은)
     * 실제 연결이 끊어진 상태인지 알 수는 없으므로 반드시 연결 상태를 직접 체크해야 한다.
     * default로 아무 것도 하지 않는 메소드가 구현되어 있으므로 반드시 제공해야하는 것은 아니다.
     *
     * @param conn CoTcp 또는 CoUdp 객체.
     */
    suspend fun onClose(conn: CONN) {}
}

/**
 * TcpCodec 코덱은
 * - inbound - codec chain의 첫 번째 codec의 decode함수는 항상 ReadBuffer 객체를 받는다.
 * - outbound - codec chain의 첫 번째 codec의 encode 함수는 반드시 ReadBuffer 객체를 반환해야 한다.
 */
typealias TcpCodec = CoCodec<CoTcp>

/**
 * UdpCodec 코덱은
 * - inbound - codec chain의 첫 번째 codec의 decode함수는 항상 DatagramPacket 객체를 받는다.
 * - outbound - codec chain의 첫 번째 codec의 encode 함수는 반드시 DatagramPacket 객체를 반환해야 한다.
 */
typealias UdpCodec = CoCodec<CoUdp>

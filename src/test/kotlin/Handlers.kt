import com.github.shanpark.conet.CoTcp
import com.github.shanpark.conet.CoUdp
import com.github.shanpark.conet.TcpHandlers
import com.github.shanpark.conet.UdpHandlers
import kotlinx.coroutines.delay
import java.lang.RuntimeException
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * TcpHandler subclass implementation 샘플.
 * 이렇게 구현하면 각 connection의 상태를 가질 수 있으며 모든 핸들러는 하나의 coroutine에서
 * 실행되므로 동기화 문제도 신경쓸 필요가 없음.
 */
class EchoHandlers: TcpHandlers() {
    companion object {
        var connCount = AtomicInteger(0) // EchoHandlers의 connection 갯수
    }

    init {
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoTcp) {
        connCount.incrementAndGet()
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        conn.write(inObj)
    }

    override suspend fun onClosed(conn: CoTcp) {
        connCount.decrementAndGet()
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        cause.printStackTrace()
    }
}

class TestHandlers(private val packetCount: Int): TcpHandlers() {
    companion object {
        var connCount = AtomicInteger(0)
    }

    private var writeCounter: Int = 0
    private var readCounter: Int = 0

    var sb = StringBuilder()

    init {
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoTcp) {
        connCount.incrementAndGet()
        delay(100)
        conn.write("(Connected)")
        writeCounter++
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        val str = inObj as String
        sb.append(str)
        readCounter++

        delay(100)
        if (readCounter > packetCount) {
            conn.sendUserEvent("(User)")
        } else {
            conn.write("(Hi)")
            writeCounter++
        }
    }

    override suspend fun onClosed(conn: CoTcp) {
        connCount.decrementAndGet()
        sb.append("(Closed)")
    }

    override suspend fun onUser(conn: CoTcp, param: Any?) {
        sb.append(param)
        conn.close()
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        println("Client OnError() - write counter:$writeCounter, read counter:$readCounter")
        cause.printStackTrace()
    }
}

/**
 * UdpHandler subclass implementation 샘플.
 * 이렇게 구현하면 각 connection의 상태를 가질 수 있으며 모든 핸들러는 하나의 coroutine에서
 * 실행되므로 동기화 문제도 신경쓸 필요가 없음.
 */
class UdpServerHandlers(private val count: Int): UdpHandlers() {
    private var readCount = 0

    init {
        codecChain.add(UdpStringCodec())
    }

    override suspend fun onConnected(conn: CoUdp) {
        throw RuntimeException("Server does not try to connect.")
    }

    override suspend fun onRead(conn: CoUdp, inObj: Any) {
        throw RuntimeException("Server doesn't connect to anybody. onRead() must not be called.")
    }

    override suspend fun onReadFrom(conn: CoUdp, inObj: Any, peerAddress: SocketAddress) {
        println("UDP Server read. [${inObj}]")
        readCount++
        delay(100)

        if (readCount >= count)
            conn.close()
        else
            conn.send(inObj, peerAddress)
    }

    override suspend fun onClosed(conn: CoUdp) {
        println("UDP Server closed.")
    }

    override suspend fun onError(conn: CoUdp, cause: Throwable) {
        cause.printStackTrace()
        conn.close()
    }
}

class UdpClientHandlers(private val count: Int): UdpHandlers() {
    companion object {
        var connCount = AtomicInteger(0)
    }

    private var readCount = 0

    init {
        codecChain.add(UdpStringCodec())
    }

    override suspend fun onConnected(conn: CoUdp) {
        println("UDP Client connected.")
        connCount.incrementAndGet()

        conn.write("Hello")
    }

    override suspend fun onRead(conn: CoUdp, inObj: Any) {
        println("UDP Client read. [$inObj]")
        readCount++
        delay(100)

        if (readCount >= count)
            conn.close()
        else
            conn.write(inObj)
    }

    override suspend fun onReadFrom(conn: CoUdp, inObj: Any, peerAddress: SocketAddress) {
        throw RuntimeException("Client connect to server. onReadFrom() must not be called.")
    }

    override suspend fun onClosed(conn: CoUdp) {
        println("UDP Client closed.")
        connCount.decrementAndGet()
    }

    override suspend fun onError(conn: CoUdp, cause: Throwable) {
        cause.printStackTrace()
        conn.close()
    }
}
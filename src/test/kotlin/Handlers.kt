import com.github.shanpark.conet.CoTcp
import com.github.shanpark.conet.CoUdp
import com.github.shanpark.conet.TcpHandlers
import com.github.shanpark.conet.UdpHandlers
import kotlinx.coroutines.delay
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
class PingPongHandlers: UdpHandlers() {
    companion object {
        var connCount = AtomicInteger(0) // EchoHandlers의 connection 갯수
    }

    private var readCount = 0

    init {
        codecChain.add(UdpStringCodec())
    }

    override suspend fun onConnected(conn: CoUdp) {
        connCount.incrementAndGet()
    }

    override suspend fun onRead(conn: CoUdp, inObj: Any) {
        readCount++
        delay(100)

        if (readCount >= 100)
            conn.close()
        else
            conn.write(inObj)
    }

    override suspend fun onClosed(conn: CoUdp) {
        connCount.decrementAndGet()
    }

    override suspend fun onError(conn: CoUdp, cause: Throwable) {
        cause.printStackTrace()
    }
}
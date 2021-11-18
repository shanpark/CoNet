
import io.github.shanpark.conet.CoConnection
import io.github.shanpark.conet.CoHandlers
import io.github.shanpark.conet.util.log
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * CoHandler subclass implementation 샘플.
 * 이렇게 구현하면 각 connection의 상태를 가질 수 있으며 모든 핸들러는 하나의 coroutine에서
 * 실행되므로 동기화 문제도 신경쓸 필요가 없음.
 */
class EchoHandlers: CoHandlers() {
    companion object {
        var connCount = AtomicInteger(0) // EchoHandlers의 connection 갯수
    }

    init {
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
        connCount.incrementAndGet()
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        conn.write(inObj)
    }

    override suspend fun onClosed(conn: CoConnection) {
        connCount.decrementAndGet()
    }

    override suspend fun onError(conn: CoConnection, cause: Throwable) {
        cause.printStackTrace()
    }
}

class TestHandlers(private val packetCount: Int): CoHandlers() {
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

    override suspend fun onConnected(conn: CoConnection) {
        connCount.incrementAndGet()
        delay(100)
        conn.write("(Connected)")
        writeCounter++
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        val str = inObj as String
        sb.append(str)
        readCounter++

        delay(100)
        if (readCounter > packetCount) {
            conn.close()
        } else {
            conn.write("(Hi)")
            writeCounter++
        }
    }

    override suspend fun onClosed(conn: CoConnection) {
        connCount.decrementAndGet()
        sb.append("(Closed)")
    }

    override suspend fun onError(conn: CoConnection, cause: Throwable) {
        log("Client OnError() - write counter:$writeCounter, read counter:$readCounter")
        cause.printStackTrace()
    }
}

import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.*
import io.github.shanpark.conet.util.log
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class StringCodec: CoCodec {
    override suspend fun encode(connection: CoConnection, inObj: Any): Any? {
        val buffer = inObj as ReadBuffer
        return buffer.readString(buffer.readableBytes)
    }

    override suspend fun decode(connection: CoConnection, outObj: Any): Any {
        val str = outObj as String
        val buffer = Buffer()
        buffer.writeString(str)
        return buffer
    }
}

class DummyCodec: CoCodec {
    override suspend fun encode(connection: CoConnection, inObj: Any): Any? {
        val str = inObj as String
        return "($str)"
    }

    override suspend fun decode(connection: CoConnection, outObj: Any): Any {
        val str = outObj as String
        return str.substring(1, str.length - 1)
    }
}

/**
 * CoHandler subclass implementation 샘플.
 * 이렇게 구현하면 각 connection의 상태를 가질 수 있으며 모든 핸들러는 하나의 coroutine에서
 * 실행되므로 동기화 문제도 신경쓸 필요가 없음.
 */
class Handlers: CoHandlers() {
    private var counter: Int = 0

    init {
        codecChain.add(StringCodec())
        codecChain.add(DummyCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
        log("Client OnConnected()")
        conn.write("(Hello CoNet)")
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        val str = inObj as String
        log("Client OnRead() - $str")

        counter++
        if (counter >= 1000)
            conn.close()
        else
            conn.write(str)
    }

    override suspend fun onClosed(conn: CoConnection) {
        log("Client OnClosed()")
    }

    override suspend fun onError(conn: CoConnection, e: Throwable) {
        log("Client OnError() - counter:$counter")
        e.printStackTrace()
    }
}

class CoNetTest {

    @Test
    @DisplayName("EchoServer Test")
    internal fun test() {
        // inline CoHandler 샘플.
        val serverAction = CoHandlers()
        serverAction.codecChain.add(StringCodec())
        serverAction.codecChain.add(DummyCodec())
        serverAction.onConnectedHandler = { _ ->
            log("Server OnConnected()")
        }
        serverAction.onReadHandler = { conn, inObj ->
            val str = inObj as String
            log("Server OnRead() - $str")

            conn.write(inObj)
        }
        serverAction.onClosedHandler = {
            log("Server OnClosed()")
        }
        serverAction.onErrorHandler = { _, e ->
            log("Server OnError()")
            e.printStackTrace()
        }

        val server = CoServer(serverAction)
            .start(InetSocketAddress("localhost", 2323))

        log("Server started.")

        Thread.sleep(100)

        for (inx in 0 .. 100)
            CoClient(Handlers())
                .connect(InetSocketAddress("localhost", 2323))

        CoClient(Handlers())
            .connect(InetSocketAddress("localhost", 2323))
            .await()

        Thread.sleep(1000)

        println("stop     -> ${System.currentTimeMillis()}")
        server.stop().await()
        println("stop end -> ${System.currentTimeMillis()}")
    }
}
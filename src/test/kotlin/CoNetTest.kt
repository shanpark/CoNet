import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.*
import io.github.shanpark.conet.util.log
import kotlinx.coroutines.delay
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

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
class ServerHandlers: CoHandlers() {
    companion object {
        var connCount = AtomicInteger(0)
    }

    init {
        codecChain.add(StringCodec())
        codecChain.add(DummyCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
//        log("Server OnConnected() - connCount: ${connCount.incrementAndGet()}")
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        conn.write(inObj)
    }

    override suspend fun onClosed(conn: CoConnection) {
//        log("Server OnClosed() - connCount: ${connCount.decrementAndGet()}")
    }

    override suspend fun onError(conn: CoConnection, e: Throwable) {
        log("Server OnError()")
        e.printStackTrace()
    }
}

class ClientHandlers: CoHandlers() {
    companion object {
        var connCount = AtomicInteger(0)
    }

    private var counter: Int = 0

    init {
        codecChain.add(StringCodec())
        codecChain.add(DummyCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
        val count = connCount.incrementAndGet()
        if (count < 10)
            log("Client OnConnected() - connCount: $count")

        delay(100)
        conn.write("(Hello CoNet)")
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        val str = inObj as String
        delay(100)
        counter++
        if (counter >= 300)
            conn.close()
        else
            conn.write(str)
    }

    override suspend fun onClosed(conn: CoConnection) {
        val count = connCount.decrementAndGet()
        if (count < 10)
            log("Client OnClosed() - connCount: $count")
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
//        val serverAction = CoHandlers()
//        serverAction.codecChain.add(StringCodec())
//        serverAction.codecChain.add(DummyCodec())
//        serverAction.onConnectedHandler = { _ ->
//            log("Server OnConnected()")
//        }
//        serverAction.onReadHandler = { conn, inObj ->
//            val str = inObj as String
////            log("Server OnRead() - $str")
//
//            conn.write(inObj)
//        }
//        serverAction.onClosedHandler = {
//            log("Server OnClosed()")
//        }
//        serverAction.onErrorHandler = { _, e ->
//            log("Server OnError()")
//            e.printStackTrace()
//        }

        val server = CoServer(ServerHandlers())
            .start(InetSocketAddress("localhost", 2323))
        log("Server started.")
        Thread.sleep(100)

        try {
            val clientList: MutableList<CoClient> = mutableListOf()
            for (inx in 1 .. 10) {
                val client = CoClient(ClientHandlers())
                    .connect(InetSocketAddress("localhost", 2323))
                clientList.add(client)
                Thread.yield()
            }
            println("All client started.")

            for (client in clientList)
                client.await()
            println("All client stopped.")
        } finally {
            println("server.stop().await()")
            server.stop().await()
            println("server.stop().await() End.")

            println("Server: ${ServerHandlers.connCount.get()}, Client: ${ClientHandlers.connCount.get()}")
        }
    }
}
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

    private var readCount = 0

    init {
        codecChain.add(StringCodec())
        codecChain.add(DummyCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
//        log("Server OnConnected() - connCount: ${connCount.incrementAndGet()}")
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        readCount++
        conn.write(inObj)
    }

    override suspend fun onClosed(conn: CoConnection) {
        if (readCount < CoNetTest.PACKET_COUNT)
            log("PacketCount not enough: ${readCount}")
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

    private var wcounter: Int = 0
    private var rcounter: Int = 0

    init {
        codecChain.add(StringCodec())
        codecChain.add(DummyCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
        val count = connCount.incrementAndGet()
        if (count % CoNetTest.CLIENT_UNIT == 0)
            log("Client OnConnected() - connCount: $count")

        delay(100)
        conn.write("(Hello CoNet)")
        wcounter++
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        val str = inObj as String
        delay(100)
        rcounter++
        if (rcounter >= CoNetTest.PACKET_COUNT) {
            conn.close()
        } else {
            conn.write(str)
            wcounter++
        }
    }

    override suspend fun onClosed(conn: CoConnection) {
        val count = connCount.decrementAndGet()
        if (count % CoNetTest.CLIENT_UNIT == 0)
            log("Client OnClosed() - connCount: $count")
    }

    override suspend fun onError(conn: CoConnection, e: Throwable) {
        log("Client OnError() - wcounter:$wcounter, rcounter:$rcounter")
        e.printStackTrace()
    }
}

class TimeCheckHandlers: CoHandlers() {
    private var rcounter: Int = 0

    private var lastTime: Long = 0
    private var totalTime: Long = 0

    init {
        codecChain.add(StringCodec())
        codecChain.add(DummyCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
        val count = ClientHandlers.connCount.incrementAndGet()
        if (count % CoNetTest.CLIENT_UNIT == 0)
            log("Client OnConnected() - connCount: $count")

        delay(100)
        conn.write("(Hello CoNet)")
        lastTime = System.currentTimeMillis()
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        val now = System.currentTimeMillis()
        totalTime += (now - lastTime)
        rcounter++

        val str = inObj as String
        delay(100)

        if (rcounter >= CoNetTest.PACKET_COUNT)
            conn.close()
        else
            conn.write(str)
        lastTime = System.currentTimeMillis()
    }

    override suspend fun onClosed(conn: CoConnection) {
        val count = ClientHandlers.connCount.decrementAndGet()
        if (count % CoNetTest.CLIENT_UNIT == 0)
            log("Client OnClosed() - connCount: $count")

        val avg = totalTime.toDouble() / rcounter.toDouble()
        log("==> Average response time: $avg ms")
    }

    override suspend fun onError(conn: CoConnection, e: Throwable) {
        log("Client OnError() - rcounter:$rcounter")
        e.printStackTrace()
    }
}

class CoNetTest {
    companion object {
        const val CLIENT_MAX = 28000
        const val CLIENT_UNIT = (CLIENT_MAX / 10)
        const val PACKET_COUNT = (CLIENT_MAX / 15)
    }

    @Test
    @DisplayName("EchoServer Test")
    internal fun test() {
        // inline CoHandler 샘플.
/*
        val serverAction = CoHandlers()
        serverAction.codecChain.add(StringCodec())
        serverAction.codecChain.add(DummyCodec())
        serverAction.onConnectedHandler = { _ ->
            log("Server OnConnected()")
        }
        serverAction.onReadHandler = { conn, inObj ->
            val str = inObj as String
//            log("Server OnRead() - $str")

            conn.write(inObj)
        }
        serverAction.onClosedHandler = {
            log("Server OnClosed()")
        }
        serverAction.onErrorHandler = { _, e ->
            log("Server OnError()")
            e.printStackTrace()
        }
*/

        val server = CoServer { ServerHandlers() }
            .start(InetSocketAddress("localhost", 2323))
        log("Server started.")
        Thread.sleep(100)

        try {
            val clientList: MutableList<CoClient> = mutableListOf()
            for (inx in 1 .. CLIENT_MAX) {
                val client =
                    if (inx == (CLIENT_MAX / 2)) {
                        CoClient(TimeCheckHandlers())
                            .connect(InetSocketAddress("localhost", 2323))
                    } else {
                        CoClient(ClientHandlers())
                            .connect(InetSocketAddress("localhost", 2323))
                    }
                clientList.add(client)
                Thread.sleep(10)
            }
            println("All client started. (client: ${clientList.size}개)")

            clientList.first().await() // 첫번째 client가 종료될 떄 까지 기다린다.
            println("First client ends.")
            while (clientList.isNotEmpty()) {
                val prevSize = clientList.size
                val it = clientList.iterator()
                while (it.hasNext()) {
                    val client = it.next()
                    client.await(1000)
                    if (!client.isRunning())
                        it.remove()
                }
                if (prevSize == clientList.size)
                    break
            }
            if (clientList.isNotEmpty()) {
                println("Some client not stopped. [Not Ended: ${clientList.size}]")
                val it = clientList.iterator()
                while (it.hasNext()) {
                    val client = it.next()
                    if (client.isRunning()) {
                        client.stop().await(1000 * 5)
                        if (!client.isRunning())
                            it.remove()
                    } else {
                        it.remove()
                    }
                }

                println("Give up stopping. [Not Ended: ${clientList.size}]")
            } else {
                println("All client stopped.")
            }
        } finally {
            println("server.stop().await()")
            server.stop().await()
            println("server.stop().await() End.")

            println("Server: ${ServerHandlers.connCount.get()}, Client: ${ClientHandlers.connCount.get()}")
        }
    }
}
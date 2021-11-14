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

class CoNetTest {

    @Test
    @DisplayName("EchoServer Test")
    internal fun test() {
        val serverAction = CoAction()
        serverAction.codecChain.add(StringCodec())
        serverAction.onConnectedHandler = { context ->
            log("Server OnConnected()")
        }
        serverAction.onReadHandler = { context, inObj ->
            val str = inObj as String
            log("Server OnRead() - $str")

            context.write(inObj)
        }
        serverAction.onClosedHandler = {
            log("Server OnClosed()")
        }
        serverAction.onErrorHandler = { context, e ->
            log("Server OnError()")
            e.printStackTrace()
        }

        val server = CoServer(serverAction)
            .start(InetSocketAddress("localhost", 2323))

        log("Server started.")

        Thread.sleep(100)

        val clientAction = CoAction()
        clientAction.codecChain.add(StringCodec())
        clientAction.onConnectedHandler = { context ->
            log("Client OnConnected()")
            context.write("Hello Server!!!")
        }
        clientAction.onReadHandler = { context, inObj: Any ->
            val str = inObj as String
            log("Client OnRead() - $str")

            context.close()
        }
        clientAction.onClosedHandler = {
            log("Client OnClosed()")
        }
        clientAction.onErrorHandler = { context, e ->
            log("Client OnError()")
            e.printStackTrace()
        }

        CoClient(clientAction)
            .connect(InetSocketAddress("localhost", 2323))

        Thread.sleep(2100)
        println("stop    -> ${System.currentTimeMillis()}")
        server.stop()
        server.await()
        println("stop end-> ${System.currentTimeMillis()}")

//        while (true)
//            Thread.sleep(1000)
    }
}
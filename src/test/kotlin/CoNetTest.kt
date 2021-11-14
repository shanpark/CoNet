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
        val serverPipeline = CoAction()
        serverPipeline.codecChain.add(StringCodec())
        serverPipeline.onConnectedHandler = { context ->
            log("Server OnConnected()")
        }
        serverPipeline.onReadHandler = { context, inObj ->
            val str = inObj as String
            log("Server OnRead() - $str")

            context.write(str)
        }
        serverPipeline.onClosedHandler = {
            log("Server OnClosed()")
        }
        serverPipeline.onErrorHandler = { context, e ->
            log("Server OnError()")
            e.printStackTrace()
        }

        CoServer(serverPipeline)
            .start(InetSocketAddress("localhost", 2323))

        log("Server started.")

        Thread.sleep(100)

        val clientPipeline = CoAction()
        clientPipeline.codecChain.add(StringCodec())
        clientPipeline.onConnectedHandler = { context ->
            log("Client OnConnected()")
            context.write("Hello Server!!!")
        }
        clientPipeline.onReadHandler = { context, inObj: Any ->
            val str = inObj as String
            log("Client OnRead() - $str")

//            context.write(str)
            context.close()
        }
        clientPipeline.onClosedHandler = {
            log("Client OnClosed()")
        }
        clientPipeline.onErrorHandler = { context, e ->
            log("Client OnError()")
            e.printStackTrace()
        }

        CoClient(clientPipeline)
            .connect(InetSocketAddress("localhost", 2323))

        while (true)
            Thread.sleep(1000)
//        server.await()
    }
}
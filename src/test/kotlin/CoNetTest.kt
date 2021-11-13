import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.CoClient
import io.github.shanpark.conet.CoPipeline
import io.github.shanpark.conet.CoServer
import io.github.shanpark.conet.util.log
import kotlinx.coroutines.delay
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class CoNetTest {

    @Test
    @DisplayName("EchoServer Test")
    internal fun test() {
        val serverPipeline = CoPipeline()
        serverPipeline.addOnConnected { context ->
            log("Server OnConnected()")
        }
        serverPipeline.addOnRead { context, inObj ->
            val buffer = (inObj as ReadBuffer)
            val str = buffer.readString(buffer.readableBytes)
            log("Server OnRead() - $str")
            context.write(str)
            return@addOnRead null
        }
        serverPipeline.addOnWrite { _, outObj ->
            val str = outObj as String
            val buffer = Buffer()
            buffer.writeString(str)
            return@addOnWrite buffer // 최종 핸들러는 ReadBuffer를 반환해야 한다.
        }
        serverPipeline.addOnClosed {
            log("Server OnClosed()")
        }
        serverPipeline.addOnError { context, e ->
            log("Server OnError()")
            e.printStackTrace()
        }

        val server = CoServer(serverPipeline)
            .start(InetSocketAddress("localhost", 2323))

        log("Server started.")

        Thread.sleep(100)

        val clientPipeline = CoPipeline()
        clientPipeline.addOnConnected { context ->
            log("Client OnConnected()")

//            context.close()
            context.write("Hello Server!!!")
        }
        clientPipeline.addOnRead { context, inObj ->
            val buffer = (inObj as ReadBuffer)
            val str = buffer.readString(buffer.readableBytes)
            log("Client OnRead() - $str")

            context.close()
            return@addOnRead null
        }
        clientPipeline.addOnWrite { _, outObj ->
            val str = outObj as String
            val buffer = Buffer()
            buffer.writeString(str)
            return@addOnWrite buffer // 최종 핸들러는 ReadBuffer를 반환해야 한다.
        }
        clientPipeline.addOnClosed {
            log("Client OnClosed()")
        }
        clientPipeline.addOnError { context, e ->
            log("Client OnError()")
            e.printStackTrace()
        }

        val client = CoClient(clientPipeline)
        client.connect(InetSocketAddress("localhost", 2323))

        while (true)
            Thread.sleep(1000)
//        server.await()
    }
}
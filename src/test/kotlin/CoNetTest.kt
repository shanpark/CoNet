import io.github.shanpark.buffers.Buffer
import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.CoPipeline
import io.github.shanpark.conet.CoServer
import io.github.shanpark.conet.util.log
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class CoNetTest {

    @Test
    @DisplayName("EchoServer Test")
    internal fun test() {
        val serverPipeline = CoPipeline()
        serverPipeline.addOnConnected {
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
            log("Server OnWrite() - $str")

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

        while (true)
            Thread.sleep(1000)
//        server.await()
    }
}
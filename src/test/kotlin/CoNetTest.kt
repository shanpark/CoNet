import io.github.shanpark.conet.CoPipeline
import io.github.shanpark.conet.CoServer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class CoNetTest {

    @Test
    @DisplayName("EchoServer Test")
    internal fun test() {
        val serverPipeline = CoPipeline()
        serverPipeline.addOnConnected {
            println("Server OnConnected()")
        }
        serverPipeline.addOnRead { context, inObj ->
            println("Server OnRead()")
            return@addOnRead null
        }
        serverPipeline.addOnWrite { context, outObj ->
            println("Server OnWrite()")
            return@addOnWrite outObj
        }
        serverPipeline.addOnClosed {
            println("Server OnClosed()")
        }
        serverPipeline.addOnError { context, e ->
            println("Server OnError()")
            e.printStackTrace()
        }

        val server = CoServer(serverPipeline)
            .start(InetSocketAddress("localhost", 2323))

        println("Server started.")

        while (true)
            Thread.sleep(1000)
//        server.await()
    }
}
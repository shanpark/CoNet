import io.github.shanpark.conet.EventPipeline
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class CoNetTest {

    @Test
    @DisplayName("EchoServer Test")
    internal fun test() {
        val serverPipeline = EventPipeline()
        serverPipeline.addOnConnected {
            println("Server OnConnected()")
        }
        serverPipeline.addOnRead { context: ConnectionContext, inObj: Any ->
            println("Server OnRead()")
            return@addOnRead null
        }
        serverPipeline.addOnWrite { context: ConnectionContext, outObj: Any ->
            println("Server OnWrite()")
            return@addOnWrite outObj
        }
        serverPipeline.addOnClosed { context: ConnectionContext ->
            println("Server OnClosed()")
        }
        serverPipeline.addOnError { context: ConnectionContext, e: Throwable ->
            println("Server OnError()")
            e.printStackTrace()
        }
        Server(serverPipeline)
            .start(InetSocketAddress("localhost", 2323))

        println("Server started.")

        val clientPipeline = EventPipeline()
        clientPipeline.addOnConnected {
            println("Client OnConnected()")
        }
        clientPipeline.addOnRead { context: ConnectionContext, inObj: Any ->
            println("Client OnRead()")
            return@addOnRead null
        }
        clientPipeline.addOnWrite { context: ConnectionContext, outObj: Any ->
            println("Client OnWrite()")
            return@addOnWrite outObj
        }
        clientPipeline.addOnClosed { context: ConnectionContext ->
            println("Client OnClosed()")
        }
        clientPipeline.addOnError { context: ConnectionContext, e: Throwable ->
            println("Client OnError()")
//            e.printStackTrace()
        }

        Client(clientPipeline)
            .connect(InetSocketAddress("localhost", 2323))
            .await()

        Thread.sleep(5000)
        println("Program Ends.")
    }
}
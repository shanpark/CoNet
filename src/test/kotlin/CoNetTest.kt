import io.github.shanpark.buffers.ReadBuffer
import io.github.shanpark.conet.Client
import io.github.shanpark.conet.ConnectionContext
import io.github.shanpark.conet.EventPipeline
import io.github.shanpark.conet.Server
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
            println("Server addOnRead()")
            val buffer = inObj as ReadBuffer
            context.write(buffer)
            return@addOnRead null
        }
        serverPipeline.addOnWrite { context: ConnectionContext, outObj: Any ->
            println("Server addOnWrite()")
            return@addOnWrite outObj
        }
        serverPipeline.addOnClosed { context: ConnectionContext ->
            println("Server addOnClosed()")
        }
        serverPipeline.addOnError { context: ConnectionContext, e: Throwable ->
            context.close()
            e.printStackTrace()
        }
        Server(serverPipeline).start(InetSocketAddress("localhost", 2323))

        val clientPipeline = EventPipeline()
        clientPipeline.addOnConnected {
            println("Client OnConnected()")
            it.write("Hello")
        }
        clientPipeline.addOnRead { context: ConnectionContext, inObj: Any ->
            println("Client addOnRead()")
            val buffer = inObj as ReadBuffer
            val string = buffer.readString(buffer.readableBytes)
            println(string)
            context.close()
            return@addOnRead null
        }
        clientPipeline.addOnWrite { context: ConnectionContext, outObj: Any ->
            println("Client addOnWrite()")
            return@addOnWrite outObj
        }
        clientPipeline.addOnClosed { context: ConnectionContext ->
            println("Client addOnClosed()")
        }
        clientPipeline.addOnError { context: ConnectionContext, e: Throwable ->
            context.close()
            e.printStackTrace()
        }
        Client(clientPipeline)
            .connect(InetSocketAddress("localhost", 2323))
            .await()
    }
}
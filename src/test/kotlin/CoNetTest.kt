import com.github.shanpark.conet.CoClient
import com.github.shanpark.conet.CoHandlers
import com.github.shanpark.conet.CoServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class CoNetTest {

    @Test
    @DisplayName("Yahoo client connect/stop Test")
    internal fun connectStop() {
        val client = CoClient(CoHandlers())
            .connect(InetSocketAddress("localhost", 2323))

        Thread.sleep(100)
        assertThat(client.isRunning()).isTrue

        Thread.sleep(100)
        client.stop().await()
        assertThat(client.isRunning()).isFalse
    }

    @Test
    @DisplayName("Basic server/client Test")
    internal fun basic() {
        val server = CoServer { EchoHandlers() }
            .start(InetSocketAddress(2323))

        val handlers = TestHandlers(5)
        CoClient(handlers)
            .connect(InetSocketAddress("localhost", 2323))
            .await()

        assertThat(server.isRunning()).isTrue

        server.stop().await()
        assertThat(handlers.sb.toString()).isEqualTo("(Connected)(Hi)(Hi)(Hi)(Hi)(Hi)(Closed)")
    }

    @Test
    @DisplayName("1000 client Test")
    internal fun client1000() {
        val CLIENT_MAX = 1000
        val PACKET_COUNT = (CLIENT_MAX / 5)

        val server = CoServer { EchoHandlers() }
            .start(InetSocketAddress(2323))
        println("Server started")

        try {
            val clientList: MutableList<CoClient> = mutableListOf()
            for (inx in 1 .. CLIENT_MAX) {
                val client = CoClient(TestHandlers(PACKET_COUNT))
                    .connect(InetSocketAddress("localhost", 2323))
                clientList.add(client)
                Thread.sleep(5)
            }
            println("All client started. (client: ${clientList.size})")

            Thread.sleep(100)
            assertThat(EchoHandlers.connCount.get()).isEqualTo(CLIENT_MAX)
            assertThat(TestHandlers.connCount.get()).isEqualTo(CLIENT_MAX)

            clientList.first().await() // 첫번째 client가 종료될 떄 까지 기다린다.
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

            if (clientList.isNotEmpty())
                println("Some client not stopped. [Not Ended: ${clientList.size}]")
            else
                println("All client stopped.")

            assertThat(clientList.isEmpty()).isTrue
            assertThat(EchoHandlers.connCount.get()).isEqualTo(0)
            assertThat(TestHandlers.connCount.get()).isEqualTo(0)
        } finally {
            server.stop().await()
        }
    }
}
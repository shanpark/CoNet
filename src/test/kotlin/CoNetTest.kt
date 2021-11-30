import com.github.shanpark.conet.CoClient
import com.github.shanpark.conet.CoHandlers
import com.github.shanpark.conet.CoServer
import com.github.shanpark.conet.CoUdp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

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
        assertThat(handlers.sb.toString()).isEqualTo("(Connected)(Hi)(Hi)(Hi)(Hi)(Hi)(User)(Closed)")
    }

    @Test
    @DisplayName("1000 client Test")
    internal fun client1000() {
        val CLIENT_MAX = 100
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

    @Test
    @DisplayName("TLS 1000 client Test")
    internal fun tlsClient1000() {
        val CLIENT_MAX = 100 // 4000개는 메모리 부족. 3000, 16ms, 4 정도가 적당
        val CONNECT_DELAY = 16L // 16ms delay
        val PACKET_COUNT = (CLIENT_MAX / 3.8).toInt()

        val keyfile = "./src/test/resources/cert.keystore"
        val keystorePassword = "certpassword"
        val keyPassword = "certpassword"

        val keyStore = KeyStore.getInstance("JKS")
        val keyStoreIS: InputStream = FileInputStream(keyfile)
        keyStoreIS.use {
            keyStore.load(it, keystorePassword.toCharArray())
        }
        val kmf: KeyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, keyPassword.toCharArray())
        val keyManagers = kmf.keyManagers

        // 서버용 SSLContext 생성
        val serverSslContext = SSLContext.getInstance("TLSv1.3") // 그냥 "TLS"를 지정하면 1.2가 default다.
        serverSslContext.init(keyManagers, arrayOf(BlindTrustManager()), SecureRandom())

        // 서버 시작
        val server = CoServer { EchoHandlers(true, serverSslContext) }
            .start(InetSocketAddress(2323))
        println("Server started")

        // 클라이언트용 SSLContext 생성
        val clientSslContext = SSLContext.getInstance("TLSv1.3") // 그냥 "TLS"를 지정하면 1.2가 default다.
        clientSslContext.init(null, arrayOf(BlindTrustManager()), null)

        try {
            val clientList: MutableList<CoClient> = mutableListOf()
            for (inx in 1 .. CLIENT_MAX) {
                val client = CoClient(TestHandlers(PACKET_COUNT, true, clientSslContext))
                    .connect(InetSocketAddress("localhost", 2323))
                clientList.add(client)
                Thread.sleep(CONNECT_DELAY) // 초당 갯수를 조정하기 위해 delay를 준다. 2000개 16ms 성공. 15ms는 접속 속도가 밀리는 현상이 있음.
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
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            server.stop().await()
        }
    }

    @Test
    @DisplayName("UDP Test")
    internal fun udp() {
        val udpServer = CoUdp(UdpServerHandlers(10))
        udpServer.bind(InetSocketAddress(2222))
        println("UDP Server bound.")

        val udpClient = CoUdp(UdpClientHandlers(9))
        udpClient.connect(InetSocketAddress("localhost", 2222))

        udpClient.await()
        udpServer.stop().await()
    }

    class BlindTrustManager: X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate>? {
            return null
        }

        override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        }

        override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        }
    }

    @Test
    @DisplayName("TLS Web Client Test")
    internal fun tls() {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(BlindTrustManager()), null)
        val client = CoClient(TlsHandlers(sslContext))
        client.connect(InetSocketAddress(TlsHandlers.HOST, 443))
        client.await()
    }

    @Test
    @DisplayName("TLS Server Test")
    internal fun tlsServer() {
        val keyfile = "./src/test/resources/cert.keystore"
        val keystorePassword = "certpassword"
        val keyPassword = "certpassword"

        val keyStore = KeyStore.getInstance("JKS")
        val keyStoreIS: InputStream = FileInputStream(keyfile)
        keyStoreIS.use {
            keyStore.load(it, keystorePassword.toCharArray())
        }
        val kmf: KeyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, keyPassword.toCharArray())
        val keyManagers = kmf.keyManagers

        // 서버용 SSLContext 생성
        val serverSslContext = SSLContext.getInstance("TLSv1.3") // 그냥 "TLS"를 지정하면 1.2가 default다.
        serverSslContext.init(keyManagers, arrayOf(BlindTrustManager()), SecureRandom())

        // 서버 시작.
        val server = CoServer { TlsEchoHandlers(serverSslContext) }
            .start(InetSocketAddress(10080))
        println("TLS Server started.")

        // 클라이언트용 SSLContext 생성
        val clientSslContext = SSLContext.getInstance("TLSv1.3") // 그냥 "TLS"를 지정하면 1.2가 default다.
        clientSslContext.init(null, arrayOf(BlindTrustManager()), null)

        // 클라이언트 시작.
        val handlers = TlsTestHandlers(clientSslContext, 5)
        CoClient(handlers)
            .connect(InetSocketAddress("localhost", 10080))
            .await()

        assertThat(server.isRunning()).isTrue

        server.stop().await()
        assertThat(handlers.sb.toString()).isEqualTo("(Connected)(Hi)(Hi)(Hi)(Hi)(Hi)(User)(Closed)")
        assertThat(server.isRunning()).isFalse
    }
}
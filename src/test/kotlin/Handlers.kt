import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.CoTcp
import com.github.shanpark.conet.CoUdp
import com.github.shanpark.conet.CoTcpHandlers
import com.github.shanpark.conet.CoUdpHandlers
import com.github.shanpark.conet.codec.TlsCodec
import kotlinx.coroutines.delay
import java.lang.RuntimeException
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

/**
 * TcpHandler subclass implementation 샘플.
 * 이렇게 구현하면 각 connection의 상태를 가질 수 있으며 모든 핸들러는 하나의 coroutine에서
 * 실행되므로 동기화 문제도 신경쓸 필요가 없음.
 */
class EchoHandlers(tls: Boolean = false, sslContext: SSLContext? = null): CoTcpHandlers() {
    companion object {
        var connCount = AtomicInteger(0) // EchoHandlers의 connection 갯수
    }

    init {
        if (tls)
            codecChain.add(TlsCodec(sslContext!!, false))
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoTcp) {
        connCount.incrementAndGet()
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        conn.write(inObj)
    }

    override suspend fun onClosed(conn: CoTcp) {
        connCount.decrementAndGet()
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        cause.printStackTrace()
        conn.close()
    }
}

class TestHandlers(private val packetCount: Int, tls: Boolean = false, sslContext: SSLContext? = null): CoTcpHandlers() {
    companion object {
        var connCount = AtomicInteger(0)
    }

    private var writeCounter: Int = 0
    private var readCounter: Int = 0

    var sb = StringBuilder()

    init {
        if (tls)
            codecChain.add(TlsCodec(sslContext!!, true))
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoTcp) {
        connCount.incrementAndGet()
        delay(100)
        conn.write("(Connected)")
        writeCounter++
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        val str = inObj as String
        sb.append(str)
        readCounter++

        delay(100)
        if (readCounter > packetCount) {
            conn.sendUserEvent("(User)")
        } else {
            conn.write("(Hi)")
            writeCounter++
        }
    }

    override suspend fun onClosed(conn: CoTcp) {
        connCount.decrementAndGet()
        sb.append("(Closed)")
    }

    override suspend fun onUser(conn: CoTcp, param: Any?) {
        sb.append(param)
        conn.close()
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        println("Client OnError() - write counter:$writeCounter, read counter:$readCounter")
        cause.printStackTrace()
        conn.close()
    }
}

/**
 * UdpHandler subclass implementation 샘플.
 * 이렇게 구현하면 각 connection의 상태를 가질 수 있으며 모든 핸들러는 하나의 coroutine에서
 * 실행되므로 동기화 문제도 신경쓸 필요가 없음.
 */
class UdpServerHandlers(private val count: Int): CoUdpHandlers() {
    private var readCount = 0

    init {
        codecChain.add(UdpStringCodec())
    }

    override suspend fun onConnected(conn: CoUdp) {
        throw RuntimeException("Server does not try to connect.")
    }

    override suspend fun onRead(conn: CoUdp, inObj: Any) {
        throw RuntimeException("Server doesn't connect to anybody. onRead() must not be called.")
    }

    override suspend fun onReadFrom(conn: CoUdp, inObj: Any, peerAddress: SocketAddress) {
        println("UDP Server read. [${inObj}]")
        readCount++
        delay(100)

        if (readCount >= count)
            conn.close()
        else
            conn.send(inObj, peerAddress)
    }

    override suspend fun onClosed(conn: CoUdp) {
        println("UDP Server closed.")
    }

    override suspend fun onError(conn: CoUdp, cause: Throwable) {
        cause.printStackTrace()
        conn.close()
    }
}

class UdpClientHandlers(private val count: Int): CoUdpHandlers() {
    companion object {
        var connCount = AtomicInteger(0)
    }

    private var readCount = 0

    init {
        codecChain.add(UdpStringCodec())
    }

    override suspend fun onConnected(conn: CoUdp) {
        println("UDP Client connected.")
        connCount.incrementAndGet()

        conn.write("Hello")
    }

    override suspend fun onRead(conn: CoUdp, inObj: Any) {
        println("UDP Client read. [$inObj]")
        readCount++
        delay(100)

        if (readCount >= count)
            conn.close()
        else
            conn.write(inObj)
    }

    override suspend fun onReadFrom(conn: CoUdp, inObj: Any, peerAddress: SocketAddress) {
        throw RuntimeException("Client connect to server. onReadFrom() must not be called.")
    }

    override suspend fun onClosed(conn: CoUdp) {
        println("UDP Client closed.")
        connCount.decrementAndGet()
    }

    override suspend fun onError(conn: CoUdp, cause: Throwable) {
        cause.printStackTrace()
        conn.close()
    }
}

class TlsHandlers(sslContext: SSLContext): CoTcpHandlers() {
    companion object {
        const val HOST = "www.daum.net"
    }

    private var totalReceived = 0

    init {
        codecChain.add(TlsCodec(sslContext, true))
    }

    override suspend fun onConnected(conn: CoTcp) {
        println("TLS OnConnected()")
        val buffer = Buffer(1024)
        buffer.writeString(
            "GET / HTTP/1.1\r\nHost: $HOST\r\nConnection: keep-alive\r\nsec-ch-ua: \"Google Chrome\";v=\"95\", \"Chromium\";v=\"95\", \";Not A Brand\";v=\"99\"\r\nsec-ch-ua-mobile: ?0\r\nsec-ch-ua-platform: \"Linux\"\r\nUpgrade-Insecure-Requests: 1\r\nUser-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.54 Safari/537.36\r\nAccept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9\r\nSec-Fetch-Site: none\r\nSec-Fetch-Mode: navigate\r\nSec-Fetch-User: ?1\r\nSec-Fetch-Dest: document\r\nAccept-Encoding: gzip, deflate, br\r\nAccept-Language: ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7\r\nCookie: webid=21a07c5e84db4035a7a5484bfba554fc; webid_ts=1576587166477; TIARA=MPAx4FPL_itX1rsC8ck7ctCDggnQihIKVW9CJXJsN9Lf9dMxOdjp2pdnATslBvWXfOn7xF2DSoC-dJjbBWxsYbevKmCMgOFB; _T_ANO=jGdlBEUMXkPJsWuSdZyRJ4/bZp7ENTPWZR1rz1d/Et7RNw2a/8GNIvgGpzc7FaDRphFzWVauVy259G8DcTyyVtvK0GpAHTcJr5cTQwZfaCkrOoyX7kxQY5Nz0CoTNVzgpz1SoyZkdcyr/qYWhANuctgP5/ikIvc8fBo8oupQyoasjd739FA1jgHxVOK/GdBR8TNfBiJtxMi1oyxiUdB8aCx9k7TI473hSiZ+zVj/9R+RTJsfrdlyz35IXB+At2vK/UM01M1DgQxS2d4pjvFZ+ukF8qv9lr7c5twB3ALKUVzKidU8D5ESv/SE9oe0X+hqkzSYgk3mDFmmrjcbCqUtPQ==; webid_sync=1637834700768\r\n\r\n"
        )
        conn.write(buffer)
        totalReceived = 0
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        val buffer = inObj as ReadBuffer
        val length = buffer.readableBytes
        println("TLS onRead() = $length")

        totalReceived += length
        val str = buffer.readString(length)
        println("'$str'")
    }

    override suspend fun onClosed(conn: CoTcp) {
        println("TLS onClosed() = [$totalReceived]")
    }

    override suspend fun onUser(conn: CoTcp, param: Any?) {
        println("TLS OnConnected()")
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        println("TLS OnError()")
        cause.printStackTrace()
        conn.close()
    }
}

class TlsServerHandlers(sslContext: SSLContext): CoTcpHandlers() {
    private var totalReceived = 0

    init {
        codecChain.add(TlsCodec(sslContext, false))
    }

    override suspend fun onConnected(conn: CoTcp) {
        println("TLS OnConnected() [${conn.channel}]")
        totalReceived = 0
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        val buffer = inObj as ReadBuffer
        val length = buffer.readableBytes
        println("TLS onRead() = $length")

        totalReceived += length
        val str = buffer.readString(length)
        println("'$str'")
    }

    override suspend fun onClosed(conn: CoTcp) {
        println("TLS onClosed() = [$totalReceived]")
    }

    override suspend fun onUser(conn: CoTcp, param: Any?) {
        println("TLS OnConnected()")
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        println("TLS OnError()")
        cause.printStackTrace()
        conn.close()
    }
}

class TlsEchoHandlers(sslContext: SSLContext): CoTcpHandlers() {
    companion object {
        var connCount = AtomicInteger(0) // EchoHandlers의 connection 갯수
    }

    init {
        codecChain.add(TlsCodec(sslContext, false))
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoTcp) {
        connCount.incrementAndGet()
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        conn.write(inObj)
    }

    override suspend fun onClosed(conn: CoTcp) {
        connCount.decrementAndGet()
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        cause.printStackTrace()
        conn.close()
    }
}

class TlsTestHandlers(sslContext: SSLContext, private val packetCount: Int): CoTcpHandlers() {
    companion object {
        var connCount = AtomicInteger(0)
    }

    private var writeCounter: Int = 0
    private var readCounter: Int = 0

    var sb = StringBuilder()

    init {
        codecChain.add(TlsCodec(sslContext, true))
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoTcp) {
        connCount.incrementAndGet()
        delay(100)
        conn.write("(Connected)")
        writeCounter++
    }

    override suspend fun onRead(conn: CoTcp, inObj: Any) {
        val str = inObj as String
        sb.append(str)
        readCounter++

        delay(100)
        if (readCounter > packetCount) {
            conn.sendUserEvent("(User)")
        } else {
            conn.write("(Hi)")
            writeCounter++
        }
    }

    override suspend fun onClosed(conn: CoTcp) {
        connCount.decrementAndGet()
        sb.append("(Closed)")
    }

    override suspend fun onUser(conn: CoTcp, param: Any?) {
        sb.append(param)
        conn.close()
    }

    override suspend fun onError(conn: CoTcp, cause: Throwable) {
        println("Client OnError() - write counter:$writeCounter, read counter:$readCounter")
        cause.printStackTrace()
        conn.close()
    }
}

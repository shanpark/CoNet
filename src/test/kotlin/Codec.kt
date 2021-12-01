import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.*
import java.net.DatagramPacket

class StringCodec: CoTcpCodec {
    override suspend fun decode(conn: CoTcp, inObj: Any): Any? {
        val buffer = inObj as ReadBuffer
        return if (buffer.isReadable)
            buffer.readString(buffer.readableBytes)
        else
            null // codec chain 중단 조건. 모든 코덱은 중단조건을 명확하게 구현해야 한다.
    }

    override suspend fun encode(conn: CoTcp, outObj: Any): Any {
        val str = outObj as String
        val buffer = Buffer()
        buffer.writeString(str)
        return buffer
    }
}

class ParenthesesCodec: CoTcpCodec {
    override suspend fun decode(conn: CoTcp, inObj: Any): Any? {
        val str = inObj as String
        return if (str.isNotBlank())
            "($str)"
        else
            null // codec chain 중단 조건. 모든 코덱은 중단조건을 명확하게 구현해야 한다.
    }

    override suspend fun encode(conn: CoTcp, outObj: Any): Any {
        val str = outObj as String
        return str.substring(1, str.length - 1)
    }
}

class UdpStringCodec: CoUdpCodec {
    override suspend fun decode(conn: CoUdp, inObj: Any): Any? {
        val datagram = inObj as DatagramPacket
        return if (datagram.length > 0)
            String(datagram.data, datagram.offset, datagram.length)
        else
            null
    }

    override suspend fun encode(conn: CoUdp, outObj: Any): Any {
        val str = outObj as String
        val byteArray = str.toByteArray()
        return DatagramPacket(byteArray, 0, byteArray.size)
    }
}

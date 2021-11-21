import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.*

class StringCodec: TcpCodec {
    override suspend fun encode(handlers: TcpHandlers, inObj: Any): Any? {
        val buffer = inObj as ReadBuffer
        return buffer.readString(buffer.readableBytes)
    }

    override suspend fun decode(handlers: TcpHandlers, outObj: Any): Any {
        val str = outObj as String
        val buffer = Buffer()
        buffer.writeString(str)
        return buffer
    }
}

class ParenthesesCodec: TcpCodec {
    override suspend fun encode(handlers: TcpHandlers, inObj: Any): Any? {
        val str = inObj as String
        return "($str)"
    }

    override suspend fun decode(handlers: TcpHandlers, outObj: Any): Any {
        val str = outObj as String
        return str.substring(1, str.length - 1)
    }
}

class UdpStringCodec: UdpCodec {
    override suspend fun encode(handlers: UdpHandlers, inObj: Any): Any? {
        val buffer = inObj as ReadBuffer
        return buffer.readString(buffer.readableBytes)
    }

    override suspend fun decode(handlers: UdpHandlers, outObj: Any): Any {
        val str = outObj as String
        val buffer = Buffer()
        buffer.writeString(str)
        return buffer
    }
}

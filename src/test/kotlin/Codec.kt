import com.github.shanpark.buffers.Buffer
import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.CoCodec
import com.github.shanpark.conet.CoConnection

class StringCodec: CoCodec {
    override suspend fun encode(conn: CoConnection, inObj: Any): Any {
        val buffer = inObj as ReadBuffer
        return buffer.readString(buffer.readableBytes)
    }

    override suspend fun decode(conn: CoConnection, outObj: Any): Any {
        val str = outObj as String
        val buffer = Buffer()
        buffer.writeString(str)
        return buffer
    }
}

class ParenthesesCodec: CoCodec {
    override suspend fun encode(conn: CoConnection, inObj: Any): Any? {
        val str = inObj as String
        return "($str)"
    }

    override suspend fun decode(conn: CoConnection, outObj: Any): Any {
        val str = outObj as String
        return str.substring(1, str.length - 1)
    }
}

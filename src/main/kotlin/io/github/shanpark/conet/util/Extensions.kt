import io.github.shanpark.conet.CoConnection
import io.github.shanpark.conet.util.Event
import io.github.shanpark.services.util.EventPool
import java.nio.channels.SelectionKey


fun SelectionKey.on(ops: Int) {
    interestOps(interestOps() or ops)
}

fun SelectionKey.off(ops: Int) {
    interestOps(interestOps() and ops.inv())
}

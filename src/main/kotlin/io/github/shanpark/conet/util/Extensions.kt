package io.github.shanpark.conet.util

import java.nio.channels.SelectionKey

fun SelectionKey.on(ops: Int) {
    interestOps(interestOps() or ops)
}

fun SelectionKey.off(ops: Int) {
    interestOps(interestOps() and ops.inv())
}

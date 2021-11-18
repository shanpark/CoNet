package io.github.shanpark.conet.util

import java.nio.channels.SelectionKey

/**
 * SelectionKey의 interestOps에 특정 OPS bit를 on 시킨다.
 */
fun SelectionKey.on(ops: Int) {
    interestOps(interestOps() or ops)
}

/**
 * SelectionKey의 interestOps에 특정 OPS bit를 off 시킨다.
 */
fun SelectionKey.off(ops: Int) {
    interestOps(interestOps() and ops.inv())
}

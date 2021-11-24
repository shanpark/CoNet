package com.github.shanpark.conet.util

import java.nio.channels.SelectionKey

/**
 * SelectionKey의 interestOps에 특정 OPS bit를 on 시킨다.
 *
 * @param ops on시킬 OPS bit. SelectionKey.OP_XXX 형태로 선언된 값을 전달한다.
 */
internal fun SelectionKey.on(ops: Int) {
    interestOps(interestOps() or ops)
}

/**
 * SelectionKey의 interestOps에 특정 OPS bit를 off 시킨다.
 *
 * @param ops off시킬 OPS bit. SelectionKey.OP_XXX 형태로 선언된 값을 전달한다.
 */
internal fun SelectionKey.off(ops: Int) {
    interestOps(interestOps() and ops.inv())
}

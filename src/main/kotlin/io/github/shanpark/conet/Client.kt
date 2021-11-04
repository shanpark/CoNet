package io.github.shanpark.conet

import io.ktor.network.sockets.*

class Client(private val socket: ASocket, private val pipeline: EventPipeline) {
}
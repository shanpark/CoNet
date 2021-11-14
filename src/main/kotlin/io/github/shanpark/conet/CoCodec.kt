package io.github.shanpark.conet

interface CoCodec {
    suspend fun encode(connection: CoConnection, inObj: Any): Any?
    suspend fun decode(connection: CoConnection, outObj: Any): Any
}
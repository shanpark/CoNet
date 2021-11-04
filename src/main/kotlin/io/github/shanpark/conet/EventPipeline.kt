package io.github.shanpark.conet

class EventPipeline {

    fun addOnConnected(handler: (context: Context) -> Unit) {

    }

    fun addOnRead(handler: (context: Context, inObj: Any) -> Any) {

    }

    fun addOnWrite(handler: (context: Context, outObj: Any) -> Any) {

    }

    fun addOnClosed(handler: (context: Context) -> Unit) {

    }

    fun addOnError(handler: (context: Context, cause: Throwable) -> Unit) {

    }
}
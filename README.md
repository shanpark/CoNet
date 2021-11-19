# CoNet

[![](https://jitpack.io/v/shanpark/CoNet.svg)](https://jitpack.io/#shanpark/CoNet)

## Usage

### Codec sample

ReadBuffer 객체 <=> String 객체 변환. 

```kotlin
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
```

"somestring" <=> "(somestring)" 변환.

```kotlin
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
```

### Handlers sample

* CoHandlers subclass handler
  * codecChain을 StringCodec, ParenthesesCodec 순으로 구성하였으므로 아래와 같이 변환된다. 
    * remote write() -> ReadBuffer -> "String" -> "(String)" -> onRead()
    * remote onRead() <- ReadBuffer <- "String" <- "(String)" <- write()
    
```kotlin
class EchoHandlers: CoHandlers() {
    init {
        // construct codec chain 
        codecChain.add(StringCodec())
        codecChain.add(ParenthesesCodec())
    }

    override suspend fun onConnected(conn: CoConnection) {
        println("Connected !")
    }

    override suspend fun onRead(conn: CoConnection, inObj: Any) {
        conn.write(inObj)
    }

    override suspend fun onError(conn: CoConnection, cause: Throwable) {
        cause.printStackTrace()
        conn.close()
    }
}
```

* Inline handler

  Subclass 방식과 달리 inline handler 방식으로는 handler 내부의 상태(property)를 가질 수 없다.
  따라서 간단히 몇몇 이벤트에 대한 핸들러만 필요한 경우에 사용한다.

```kotlin
val handlers = CoHandlers()

// construct codec chain 
handlers.codecChain.add(StringCodec())
handlers.codecChain.add(ParenthesesCodec())

handlers.onReadHandler = { conn, inObj ->
    val buffer = inObj as ReadBuffer
    /* read from buffer */
}
handlers.onErrorHandler = { conn, cause ->
    cause.printStackTrace()
    conn.close()
}
```

### Server sample
* CoHandlers객체를 반환하는 factory 함수와 함께 CoServer 객체를 생성하고 start() 시키면 서버가 시작된다.
* 서버가 종료될 때 까지 기다리려면 await() 메소드를 이용한다.
```kotlin
val server = CoServer { EchoHandlers() }
    .start(InetSocketAddress(2323))

    /* do something */

// server.await()
```

### Client sample

* CoHandlers객체를 생성하고 CoClient 객체를 생성하여 connect()를 요청하면 된다.
* 서버 객체와 마찬가지로 접속이 종료될 때 까지 기다리려면 await() 메소드를 이용한다.

```kotlin
CoClient(SomeHandlers())
    .connect(InetSocketAddress("localhost", 2323))
```

## Install

To install the library add:

```gradle
repositories { 
   ...
   maven { url "https://jitpack.io" }
}

dependencies {
    implementation 'io.github.shanpark:CoNet:0.0.2'
}
```
package com.github.shanpark.conet

import com.github.shanpark.buffers.ReadBuffer
import com.github.shanpark.conet.util.Event
import com.github.shanpark.conet.util.EventId
import com.github.shanpark.conet.util.off
import com.github.shanpark.conet.util.on
import com.github.shanpark.services.coroutine.CoroutineService
import com.github.shanpark.services.coroutine.EventLoopCoTask
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey

class CoUdp(private val handlers: CoHandlers<CoUdp>): CoSelectable {

    /**
     * send() 호출 시 target의 주소와 데이터를 모두 담아서 SEND 이벤트를 보낼 때 parameter로 같이 보내기 위해
     * 사용되는 클래스이다.
     */
    private class SendData(val obj: Any, val peer: SocketAddress?)

    override val channel: DatagramChannel = DatagramChannel.open()

    override lateinit var selectionKey: SelectionKey

    private var task = EventLoopCoTask(::onEvent, handlers.idleTimeout, ::onIdle, ::onError)
    private val service = CoroutineService().start(task)

    private val readBuffer: ByteArray = ByteArray(64 * 1024)
    private val outBuffer: MutableList<DatagramPacket> = mutableListOf()

    init {
        channel.configureBlocking(false)
    }

    /**
     * 파라미터로 전달된 local 주소에 binding한다.
     * binding된 주소로 수신되는 데이터를 받을 수 있다.
     *
     * @param address binding할 local 주소 객체.
     */
    fun bind(address: SocketAddress): CoUdp {
        channel.bind(address)
        if (!channel.isRegistered)
            CoSelector.register(this, SelectionKey.OP_READ) // register는 bind() 후에 해줘야 한다.

        return this
    }

    /**
     * 파라미터로 전달된 remote 주소에 connect한다.
     * connect가 되고나면 연결된 peer와 주고받는 것만 가능하며 다른 주소로 주고 받는 건 안된다.
     * 따라서 read(), write() 메소드를 사용해서 데이터를 송수신하는 게 좋다.
     *
     * connect() 전에 반드시 bind()를 해야하는 건 아니다.
     * bind()가 호출되지 않은 상태에서 connect()가 호출되면 자동 할당된 포트에 binding이 된다.
     *
     * @param address binding할 remote 주소 객체.
     */
    fun connect(address: SocketAddress): CoUdp {
        channel.connect(address)
        if (!channel.isRegistered)
            CoSelector.register(this, SelectionKey.OP_READ) // register는 bind() 후에 해줘야 한다.
        runBlocking { task.sendEvent(Event.CONNECTED) }

        return this
    }

    /**
     * 파라미터로 전달된 remote 주소에 connect한다.
     * send(), receive()와 달리 read(), write() 메소드를 사용하기 위해서는 connect()를 호출해야 한다.
     *
     * disconnect가 되고 난 후라도 send(), receive()를 이용해서 어디로든 데이터를 주고 받을 수 있지만
     * read(), write()는 사용할 수 없다.
     */
    fun disconnect(): CoUdp {
        channel.disconnect()

        return this
    }

    /**
     * peer로 보낼 객체를 write한다. send()와 달리 suspend 함수가 아니다. 따라서 handler 함수 내에서 호출하는 게 아니라면
     * sendTo()를 사용해서 데이터를 전송해야 한다.
     *
     * 보내는 객체는 어떤 객체든지 상관없지만 CoHandlers 객체에 구성된 codec chain을 거쳐서 최종적으로 DatagramPacket 객체로
     * 변환되어야 한다.
     *
     * ReadBuffer 객체를 write하면 buffer의 내용은 모두 읽혀진다.
     *
     * @param outObj peer로 보낼 데이터 객체.
     * @param peer 데이터를 수신할 peer의 주소 객체.
     */
    fun sendTo(outObj: Any, peer: SocketAddress) {
        runBlocking { send(outObj, peer) }
    }

    /**
     * 실행 중단을 요청한다.
     * 비동기로 수행되며 최종적으로 내부 서비스가 종료되어야 완전히 종료된 것으로 볼 수 있다.
     * handler 함수가 아닌 외부에서는 close()는 호출할 수 없고 suspend 함수가 아닌 stop()을 호출해야 한다.
     * await() 메소드를 통해서 최종 종료시까지 대기할 수 있다.
     */
    fun stop(): CoUdp {
        runBlocking { close() }
        return this
    }

    /**
     * CoUdp의 내부 coroutine 서비스가 종료될 때 까지 대기한다.
     * 파라미터로 지정된 시간(ms)이 지나면 서비스가 종료되지 않았더라도 함수가 반환된다.
     * default 값인 0이 지정되면 서비스가 종료될 때 까지 무한 대기한다.
     *
     * @param millis 서비스 종료를 기다리는 최대 대기 시간.
     */
    fun await(millis: Long = 0) {
        service.await(millis)
    }

    /**
     * 이벤트 처리를 위해서 생성된 내부 coroutine 서비스가 실행 중인지 여부를 반환한다.
     *
     * @return coroutine 서비스가 실행중이면 true, 아니면 false.
     */
    fun isRunning(): Boolean {
        return service.isRunning()
    }

    /**
     * peer로 보낼 객체를 write한다. peer 주소를 따로 받지 않기 때문에 connect()로 연결된 상태에서만 사용가능하다.
     * suspend함수 이므로 handler 함수 내에서 사용해야 한다.
     *
     * 보내는 객체는 어떤 객체든지 상관없지만 CoHandlers 객체에 구성된 codec chain을 거쳐서 최종적으로
     * DatagramPacket 객체로 변환되어야 한다.
     *
     * ReadBuffer 객체를 write하면 buffer의 내용은 모두 읽혀진다.
     *
     * @param outObj peer로 보낼 데이터 객체.
     */
    suspend fun write(outObj: Any) {
        if (outObj is ReadBuffer)
            task.sendEvent(Event.newEvent(EventId.WRITE, outObj.readSlice(outObj.readableBytes)))
        else
            task.sendEvent(Event.newEvent(EventId.WRITE, outObj))
    }

    /**
     * peer로 보낼 객체를 write한다. connect() 되지 않은 상태에서 특정 peer 주소를 지정하여 데이터를 보낼 때
     * 사용한다. connect()가 된 상태에서는 연결된 주소 이외의 다른 주소로는 보낼 수 없다.
     *
     * 보내는 객체는 어떤 객체든지 상관없지만 CoHandlers 객체에 구성된 codec chain을 거쳐서 최종적으로
     * DatagramPacket 객체로 변환되어야 한다.
     *
     * ReadBuffer 객체를 write하면 buffer의 내용은 모두 읽혀진다.
     *
     * @param outObj peer로 보낼 데이터 객체.
     * @param peer 데이터를 수신할 peer의 주소 객체.
     */
    suspend fun send(outObj: Any, peer: SocketAddress) {
        if (outObj is ReadBuffer)
            task.sendEvent(Event.newEvent(EventId.SEND, SendData(outObj.readSlice(outObj.readableBytes), peer)))
        else
            task.sendEvent(Event.newEvent(EventId.SEND, SendData(outObj, peer)))
    }

    /**
     * open된 내부의 채널을 닫도록 요청한다.
     * 여러 handler 함수에서 서비스를 중단하고 채널을 닫고 서비스를 중단하고자 할 때는 stop() 보다는
     * suspend 함수인 close()를 호출해야 한다.
     * 
     * 이미 닫혀진 상태에서는 호출해서는 안된다.
     */
    suspend fun close() {
        task.sendEvent(Event.CLOSE)
    }

    /**
     * CoSelectable 인터페이스 구현.
     * CoSelector의 thread에서만 호출되며 Selector에 OP_READ, OP_WRITE, OP_CONNECT가 발생하면 호출된다.
     *
     * 내부 coroutine 서비스에서 처리하도록 하기 때문에 일단 channel에 해당 이벤트가 다시 발생하지 않도록
     * 처리를 즉시해야 하고 가능한 빨리 리턴하는 것이 좋다.
     * 내부적으로 발생하는 모든 exception은 전파되어서는 안되고 반드시 처리한 후에 리턴해야 한다.
     *
     * @param key selector에 의해 select된 key.
     */
    override suspend fun handleSelectedKey(key: SelectionKey) {
        try {
            if (key.isValid) {
                if (key.isReadable) {
                    selectionKey.off(SelectionKey.OP_READ) // OP_READ off. wakeup은 필요없다.
                    task.sendEvent(Event.READ)
                } else if (key.isWritable) {
                    selectionKey.off(SelectionKey.OP_WRITE) // OP_WRITE off. wakeup은 필요없다.
                    task.sendEvent(Event.WRITE) // 계속 이어서 진행.
                }
            }
        } catch (e: Throwable) {
            task.sendEvent(Event.newErrorEvent(e)) // 다른 thread이므로 event로 보내야 한다.
        }
    }

    private suspend fun onEvent(event: Event) {
        try {
            when (event.id) {
                EventId.CONNECTED -> onConnected()
                EventId.READ -> onRead()
                EventId.WRITE -> onWrite(event)
                EventId.SEND -> onSend(event)
                EventId.CLOSE -> onClose()
                EventId.CLOSED -> onClosed()
                EventId.ERROR -> onError(event)
                EventId.USER -> onUser(event)
                else -> onError(IllegalStateException())
            }
        } catch (e: Throwable) {
            onError(e)
        }
    }

    private suspend fun onConnected() {
        handlers.onConnectedHandler.invoke(this)
    }

    private suspend fun onRead() {
        val datagram: DatagramPacket? =
            if (channel.isConnected)
                internalRead()
            else
                internalReceive()
        if (datagram != null) { // null이 반환되면 중단.
            var inObj: Any? = datagram
            for (codec in handlers.codecChain) {
                inObj = codec.decode(this, inObj!!)
                if (inObj == null)
                    break
            }
            if (inObj != null) {
                if (channel.isConnected)
                    handlers.onReadHandler.invoke(this, inObj)
                else
                    handlers.onReadFromHandler.invoke(this, inObj, datagram.socketAddress)
            }
        }
        selectionKey.on(SelectionKey.OP_READ)
        CoSelector.wakeup() // 여기서는 wakeup 필요.
    }

    private suspend fun onWrite(event: Event) {
        if (event.param != null) { // retry일 때는 무조건 WRITE로 오며 peer 주소는 datagram에 저장되어있으므로 retry도 문제 없다.
            var obj: Any = event.param!!
            for (codec in handlers.codecChain.asReversed())
                obj = codec.encode(this, obj)
            if (obj is DatagramPacket) { // 최종 obj는 반드시 DatagramPacket이어야 한다.
                outBuffer.add(obj)
            }
            Event.release(event) // param이 not null인 경우 release 해줘야 한다.
        }

        sendOutBuffer()
    }

    private suspend fun onSend(event: Event) {
        val sendData = (event.param as SendData)
        val peer = sendData.peer
        var obj: Any = sendData.obj
        for (codec in handlers.codecChain.asReversed())
            obj = codec.encode(this, obj)
        if (obj is DatagramPacket) { // 최종 obj는 반드시 DatagramPacket이어야 한다.
            obj.socketAddress = peer
            outBuffer.add(obj)
        }
        Event.release(event) // param이 not null인 경우 release 해줘야 한다.

        sendOutBuffer()
    }

    private suspend fun onClose() {
        // close()하기 전에 selector unregister를 먼저 해줘야 한다.
        // selector를 unregister하지 않았도 linux에서는 문제가 없지만 macOS에서는 channel을 close()할 때
        // 가끔 무한 block되는 현상이 있다. (TCP에서만 발견되었지만 UDP도 해준다)
        CoSelector.unregister(this)
        @Suppress("BlockingMethodInNonBlockingContext")
        channel.close()

        // close()를 호출하면 CLOSE 이벤트를 보내고 이벤트가 처리될 때 까지 약간의 시간 공백이 있는데 그 사이에 OP_READ가
        // 발생해서 읽기를 요청하는 경우가 흔히 있다. 하지만 channel은 여기서 close되므로 모든 요청 event를 비워야 한다.
        // 그리고 나서 CLOSED 이벤트를 보내어 onClose()가 호출될 수 있도록 한다.
        task.clearEventQueue()
        task.sendEvent(Event.CLOSED) // 채널을 닫았으므로 CLOSED를 전송한다.
    }

    private suspend fun onClosed() {
        handlers.onClosedHandler.invoke(this)
        service.stop() // service stop 요청. 큐에 이미 있더라도 이후 event는 모두 무시된다.
    }

    private suspend fun onError(event: Event) {
        onError(event.param as Throwable)
        Event.release(event) // ERROR 이벤트는 항상 param이 null이 아니며 따라서 항상 release되어야 한다.
    }

    private suspend fun onUser(event: Event) {
        handlers.onUserHandler.invoke(this, event.param)
        Event.release(event)
    }

    private suspend fun onIdle() {
        try {
            handlers.onIdleHandler.invoke(this)
        } catch (e: Throwable) {
            onError(e)
        }
    }

    /**
     * 에러가 발생하면 호출된다.
     * onEvent, onIdle에서 exception이 발생하면 이 함수를 호출하고 service를 계속하지만
     * service가 스스로 발생시킨 exception인 경우 이 함수를 호출하고 service는 종료된다.
     * 하지만 현재는 service가 스스로 exception을 발생시킬 일은 없다.
     */
    private suspend fun onError(cause: Throwable) {
        try {
            handlers.onErrorHandler.invoke(this, cause)
        } catch (e: Throwable) {
            e.printStackTrace() // onError에서 발생한 exception을 다시 전파하면 무한 재귀 현상이 나올 수 있다.
        }
    }

    private fun internalRead(): DatagramPacket? {
        val read = channel.read(ByteBuffer.wrap(readBuffer, 0, readBuffer.size))
        return if (read > 0)
            DatagramPacket(readBuffer, 0, read)
        else // 읽은 바이트 수가 0이면 다음 packet은 없는 것이다.
            null
    }

    private fun internalReceive(): DatagramPacket? {
        val byteBuffer = ByteBuffer.wrap(readBuffer, 0, readBuffer.size)
        val peerAddress = channel.receive(byteBuffer)
        return if (peerAddress != null) {
            byteBuffer.flip()
            DatagramPacket(readBuffer, 0, byteBuffer.remaining(), peerAddress)
        } else {
            null
        }
    }

    private fun sendOutBuffer() {
        val it = outBuffer.iterator()
        while (it.hasNext()) {
            val datagram = it.next()
            val written = if (datagram.address == null)
                internalWrite(datagram)
            else
                internalSend(datagram)
            if (written <= 0) // datagram 전송은 all or nothing이다. 따라서 재시도는 0인 경우만이다.
                break
            else
                it.remove()
        }

        if (outBuffer.isNotEmpty() && selectionKey.isValid) { // 다 못보냈으면 OP_WRITE를 켜서 retry할 수 있도록 한다.
            selectionKey.on(SelectionKey.OP_WRITE)
            CoSelector.wakeup() // 여기서는 wakeup 필요.
        }
    }

    private fun internalWrite(packet: DatagramPacket): Int {
        return channel.write(ByteBuffer.wrap(packet.data, packet.offset, packet.length))
    }

    private fun internalSend(packet: DatagramPacket): Int {
        return channel.send(ByteBuffer.wrap(packet.data, packet.offset, packet.length), packet.socketAddress)
    }
}
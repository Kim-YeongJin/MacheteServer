import java.io.*
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.sql.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.concurrent.timer
import kotlin.system.exitProcess
import kotlin.text.StringBuilder

abstract class MacheteServer(
    dbUri : String ,        //데이터베이스 경로
    dbUser : String ,       //데이터베이스 아이디
    dbPass : String ,        //데이터베이스 비밀번호
    ip : String = "127.0.0.1" , // 내부 테스트
    port : Int = 5000 ,   // 접속채널 내부 테스트
    usrNum : Int = 1000 ,   // 동시접속자
    sndBuffSize : Int = 10240 ,   // 보내는 버퍼사이즈
    rcvBuffSize : Int = 10240     // 받는 버퍼사이즈
) {
    private lateinit var assc: AsynchronousServerSocketChannel
    private val mscs = MscServerControlStat(usrNum , ip , port , sndBuffSize , rcvBuffSize)   // 서버 접속 정보
    protected val handsMap: ConcurrentHashMap<SocketAddress, MctHandsMap> = ConcurrentHashMap(mscs.usrNum)  //20.01.02 변경 : 상속 클래스에서 접근 가능하도록.
    protected val snBuff = ByteBuffer.allocateDirect(mscs.sndBuffSize)  //20.01.02 변경 : 상속 클래스에서 접근 가능하도록.

    // 숫자로 시작하는 문자가 dbUri 에 들어오면, "jdbc:mariadb://" 생략 가능
    private val con = DriverManager.getConnection(if (dbUri.first().isDigit()) "jdbc:mariadb://$dbUri" else dbUri , dbUser , dbPass)
    protected val cast = ConcurrentHashMap<String, PreparedStatement>(mscs.usrNum)
    private val consoleControlMap : HashMap<Int , ConsoleControlUnit> = HashMap()
    data class ConsoleControlUnit(val text : String, val com : () -> Unit)

    init {
        qaddRoom()
        LongTimeNotSeeListener().start()
        try {
            assc = AsynchronousServerSocketChannel.open(
                AsynchronousChannelGroup.withFixedThreadPool(
                    mscs.usrNum,
                    Executors.defaultThreadFactory()
                )
            )
            assc.setOption(StandardSocketOptions.SO_RCVBUF, mscs.sndBuffSize)
            assc.bind(InetSocketAddress(mscs.ip, mscs.port))
            pr("클라이언트의 접속을 기다리고 있습니다.")
            assc.accept(null, ManyHandsMaker())
            consoleControl()
            pr("서버를 종료합니다.")
        } catch (e: java.lang.Exception) {
            e.stackTrace.forEach { pr(it.toString()) }
        }

    }

    /*
    * 추가할 로직 : 콘솔 컨트롤 로직을 만들자.
    * 1. 아스키 코드 값을 입력받으면 발동하는 로직을 만든다.
    * 2. 매개변수는 아스키코드, 코드 이다.
    * */

    protected fun addConsoleControl(key : Int , text: String , com: () -> Unit){ consoleControlMap[key] = ConsoleControlUnit(text , com) }
    private fun consoleControl() {
        addConsoleControl(48 , "서버 종료") {
            val scanner = Scanner(System.`in`)
            pr("exit_server 를 입력하면 서버를 종료합니다.")
            if(scanner.next() == "exit_server"){
                pr("서버를 종료합니다.")
                exitProcess(0)
            }else{
                pr("잘못 입력하셨습니다.")
            }
        }
        addConsoleControl(49 , "명령어 설명") {showConsoleMenu()}
        addConsoleControl(50 , "접속 중인 사용자 수") {  pr("접속 중인 클라이언트 수 : ${handsMap.size} 명")
            handsMap.forEach{
                pr(it.key)
            } }
        addConsoleControl(51 , "서버 소켓 오픈 상태 체크") {pr("서버 소켓 오픈 상태 : ${assc.isOpen}") }
        showConsoleMenu()
        while (true) {
            try {
                consoleControlMap.getValue(System.`in`.read()).com()
            }catch (e : Exception){ }    // 키가 맞는게 없으면 에러가 날 것이기 때문에, 그냥 무시.
        }
    }


    private fun showConsoleMenu() {
        println("==================================")
        println("서버 콘솔 명령키 설명")
        println("==================================")
        consoleControlMap.forEach { (k, v) -> println("${k.toChar()}. ${v.text}") }
        println("==================================")
    }

    private fun serializationMachine(protocol: Short, obj: Array<Any>) {
        snBuff.putShort(protocol)   // 프로토콜을 short 형식으로 넣는다. (2byte) 밸류가 없다면, 프로토콜만 전송된다.
        if (obj.isNotEmpty()) {
            val baos = ByteArrayOutputStream()
            val oos = ObjectOutputStream(baos)
            oos.writeObject(obj)
            val handList = baos.toByteArray()
            snBuff.putShort(handList.size.toShort())
            snBuff.put(handList)
        }
        snBuff.flip()
    }

    @Synchronized
    fun send(ara: SocketAddress, protocol: Short, vararg hand: Any) {    //현재, 버퍼 크기이상의 데이터가 들어갈 경우의 로직이 없으니, 차후 수정바람
        serializationMachine(protocol, arrayOf(*hand))
        handsMap.getValue(ara).asc.write(snBuff, null , object : CompletionHandler<Int, Void?> {
            override fun completed(result: Int?, attachment: Void?) {
                pr("전송했습니다. 보낸 패킷 크기 : ${result.toString()} 프로토콜 : $protocol")
                snBuff.clear()
            }

            override fun failed(exc: Throwable?, attachment: Void?) {
                pr("send 의 CompleteHandler 상에서 실패 : ${exc?.localizedMessage}")
                snBuff.clear()
            }
        })
    }

    // 특정한 그룹에게 보내기
    // 어떤 그룹에 입장할 때, 그 그룹에 나의 주소값(ara)를 제공한다. (ArrayList 같은데 넣어둔다.) 그리고 채팅이라도 할 때 이 함수를 사용하면 된다.
    // ArrayList 보다 복잡한 컬렉션은 쓰지 않는 것이 좋을 것 같다. 자료를 끄집어 내기 힘들면 성능이...
    @Synchronized
    fun sendGroup(aras : ArrayList<SocketAddress> , protocol: Short , vararg hand: Any){
        serializationMachine(protocol, arrayOf(*hand))
        aras.forEach {
            handsMap.getValue(it).asc.write(snBuff, null , object : CompletionHandler<Int, Void?> {
                override fun completed(result: Int?, attachment: Void?) {
                    pr("전송했습니다. 보낸 패킷 크기 : ${result.toString()} 프로토콜 : $protocol")
                    snBuff.clear()
                }

                override fun failed(exc: Throwable?, attachment: Void?) {
                    pr("send 의 CompleteHandler 상에서 실패 : ${exc?.localizedMessage}")
                    snBuff.clear()
                }
            })
        }
    }

    @Synchronized
    fun sendAll(protocol: Short, vararg hand: Any) {   //모든 사람에게 보내기
        serializationMachine(protocol, arrayOf(*hand))
        handsMap.values.forEach {
            it.asc.write(snBuff, Instant.now().epochSecond.toShort(), object : CompletionHandler<Int, Short> {
                override fun completed(result: Int?, attachment: Short) {
                    pr("${it.play_key}에게 sendAll로 전송했습니다. 보낸 패킷 크기 : $result")
                    snBuff.clear()
                }

                override fun failed(exc: Throwable?, attachment: Short) {
                    pr("CompleteHandler 상에서 실패")
                    snBuff.clear()
                }
            })
        }
    }// sendAll*/

    //역직렬화. receiveMachine 안에서 노니까, Synchronized 를 걸 필요는 없다.
    private fun deSerializationMachine(arr: ByteArray): Array<Any> {
        val bais = ByteArrayInputStream(arr)
        val ois = ObjectInputStream(bais)
        val sopo = ois.readObject() as Array<Any>
        bais.close(); ois.close()                             //try 내부에서 오류가 날 경우 close 되지 않는 이슈가 있다. 나중에 점검.
        return sopo
    }

    @Synchronized
    fun receiveMachine(ara: SocketAddress, buffer: ByteBuffer) {//수신
        try {
            //매크로 체크 (0.1초만에 같은 ip로 신호가 들어오면 무시한다.)
            if (checkMacro(ara)) {
                wrErrLog(ara , handsMap.getValue(ara).play_key, 8282)
                return
            }
            val protocol = buffer.int   // (19.10.15 변경) short 에서 보안강화를 위해 int 로 변경
            val key = handsMap.getValue(ara).play_key
            pr("클라이언트가 보낸 프로토콜 : $protocol")
            //단순한 프로토콜인지, 가져온 데이터가 있는지 체크
            if (buffer.limit() != 4) {
                val bufferSize = buffer.remaining()
                val arr = ByteArray(bufferSize)
                buffer[arr]
                sopoOpen(ara, protocol, deSerializationMachine(arr))
            } else {
                sopoProtocol(ara, protocol)
            }
            //받은 패킷을 처리한 후, 해당 프로토콜 + 해당 유저를 로그에 기록한다.
            wrLog(ara, key, protocol)
        } catch (e: StreamCorruptedException) {
            pr(" ReceiveMachine 에서 StreamCorruptedException 발생")
            e.stackTrace.forEach { pr(it) }
        } catch (e: Exception) {
            pr(" ReceiveMachine 에서 일반 Exception 발생")
            e.stackTrace.forEach { pr(it) }
        }
    }//receiveMachine

    @Synchronized
    fun disconnect(ara: SocketAddress) {
        if (handsMap.getValue(ara).asc.isOpen) {
            handsMap.getValue(ara).asc.close()
            usrKick(ara)
            pr("$ara 연결을 끊습니다.")
        } else {
            pr("이미 연결이 끊어져 있습니다.")
        }
        handsMap.remove(ara)
    }

    //0.1초만에 같은 소켓에서 새로운 신호가 오면, 매크로로 간주하고 무시해버린다. 그리고 err 로그에 기록한다.
    private fun checkMacro(ara: SocketAddress): Boolean {
        val term = System.currentTimeMillis() - handsMap.getValue(ara).lastProtocol
        handsMap.getValue(ara).lastProtocol = System.currentTimeMillis()
        return term < 100
    }

    //이 클래스의 임무는, 접속에 성공한 주소에 소켓을 연결해주는 것이다.
    inner class ManyHandsMaker : CompletionHandler<AsynchronousSocketChannel, Void?> {
        override fun completed(asc: AsynchronousSocketChannel, attachment: Void?) {
            val ara = asc.remoteAddress //소켓 채널 주소를 키값으로 쓴다. 유저키를 쓰지 않는 이유 : 다중기기 중복접속의 경우가 있기 때문.
            val buffer = ByteBuffer.allocateDirect(mscs.rcvBuffSize)
            handsMap[ara] = MctHandsMap(
                asc, buffer, 0, System.currentTimeMillis() - 101
            )  //handsMap 컬렉션에 ManyHands 객체를 등록한다.(접속하는 내내 유지되고, 접속이 끊기면 삭제함)
            asc.read(buffer, null, ManyHandsRead(ara))  //읽기 전용 버퍼를 이용해서 클라이언트에서 보내오는 신호를 읽는 함수를 재귀호출함.
            pr("$ara 클라이언트와 연결되었습니다. 접속 중인 클라이언트 수 : ${handsMap.size}")
            assc.accept(null, this) //다음 클라이언트의 접속을 accept 함.(논블록킹)
        }

        override fun failed(exc: Throwable?, attachment: Void?) {
            pr("실패. 재접속 준비")
            assc.accept(null, this)
        }
    }

    //이 클래스는 리드소켓이 데이터를 읽었을 때 성공 실패여부를 판단한다.
    inner class ManyHandsRead(private val ara: SocketAddress) : CompletionHandler<Int, Void?> {
        override fun completed(result: Int, attachment: Void?) {
            if (result > 0) {    //이 숫자는 받은 데이터의 크기를 나타낸다. 나중에 대용량 데이터를 받아도 되는 로직을 추가할것.
                try {
                    pr("받은 패킷 크기 : $result")
                    val hand = handsMap.getValue(ara) //해쉬맵에서 ManyHands(소켓, 버퍼)를 가져온다.
                    val buffer = hand.buffer
                    buffer.flip()
                    receiveMachine(ara, buffer)  //클라이언트에서 보낸 버퍼를 읽고 역직렬화 한 후 처리하는 부분. 성능에 매우 민감한 부분임.
                    buffer.clear()
                    hand.asc.read(
                        buffer, null, this
                    ) //볼일이 끝났으면, 재귀호출해서 데이터를 읽을 준비를 한다.(재귀호출 즉시 이 클래스는 가비지 클래스에 의해 사라지므로, 메모리낭비는 없다. 아마도.)
                } catch (e: Exception) {
                    pr(" ManyHandsRead 위치에서 오류 발생")
                    e.stackTrace.forEach { pr(it) }
                }

            } else {      // -1이 떴다는 것은 연결이 끊어졌다는 뜻이므로, 해쉬맵에서 ManyHands 를 제거한다.
                disconnect(ara)
            } }

        override fun failed(exc: Throwable?, attachment: Void?) {
            pr("소켓읽기가 실패했습니다.$exc")
        } }

    // 10분동안 아무것도 안하면 내쫓음
    inner class LongTimeNotSeeListener : Thread() {
        override fun run() {
            super.run()
            timer(period = 300000){
                handsMap.forEach { (key, value) ->
                    try {
                        val sleep = System.currentTimeMillis() - value.lastProtocol
                        if (sleep > 600000) {   //10분동안 조작이 없다면 강제종료
                            pr("$key 클라이언트를 강제로 접속 종료시켰습니다.  놀고 있던 시간 : $sleep 밀리초")
                            disconnect(key)
                        }
                    } catch (e : ConcurrentModificationException){
                        pr("ConcurrentModificationException 에러가 났지만, 무시하도록 하겠다.")  // 잘 안나는 오류인데 확인 필요
                    } } } } }

    data class MctHandsMap (
        val asc : AsynchronousSocketChannel,
        val buffer : ByteBuffer,
        var play_key : Int,
        var lastProtocol : Long
    )

    data class MscServerControlStat(
        var usrNum : Int,   // 동시접속자
        var ip : String, // 내부 테스트
        var port : Int,   // 접속채널 내부 테스트
        var sndBuffSize : Int,   // 보내는 버퍼사이즈
        var rcvBuffSize : Int   // 받는 버퍼사이즈
    )

    //빈번하게 쓰이는 쿼리문을 prepareStatement 해둔다. 너무 많으면 캐시메모리를 낭비하므로 주의할 것.
    //CallableStatement 로 리팩토링하는 것도 시간남아돌 때 고려해 보자.
    protected fun qadd(adress: String, sql: String) {
        cast[adress] = con.prepareStatement(sql)
    }  // cast 맵에 쿼리를 넣어두는 함수

    //qadd 쉽게 하게 해주는 함수
    protected fun qaddInsert(adress : String , table : String , column : Array<String> , where : String = ""){
        val sb = StringBuilder("insert $table(${column.joinToString(",")})value(")
        for (i in 1..column.size){
            sb.append('?')
            sb.append(if (i==column.size) ')' else ',') }
        if (where.isNotEmpty()){
            sb.append(" where ")
            sb.append(where)
        }
        qadd(adress , sb.toString())
    }

    protected fun qaddUpdate(adress : String , table : String , column : Array<String> , where : String = ""){
        val sb = StringBuilder("update $table set ")
        for (i in column.indices){
            sb.append("${column[i]}=?")
            if (i!=column.size-1){sb.append(',')}
        }
        if (where.isNotEmpty()){
            sb.append(" where ")
            sb.append(where)
        }
        qadd(adress , sb.toString())
    }

    protected fun qaddSelect(adress : String , table : String , column : Array<String> , where : String){ qadd(adress , "select ${column.joinToString(",")} from $table where $where") }

    protected fun qaddDelete(adress : String , table : String , where : String){ qadd(adress , "delete from $table where $where") }

    protected fun pr(str : Any){ println("[${DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(LocalDateTime.now(ZoneId.of("Asia/Seoul")))}] $str") }

    // 아래 호출되는 함수들은 receiveMachine 상으로 동기 실행되므로, Synchronize 가 불필요함. 다만, 비동기 실행되게 하는 방법도 생각해 봅시다.
    protected abstract fun sopoProtocol(ara: SocketAddress, protocol: Int)
    protected abstract fun sopoOpen(ara: SocketAddress, protocol: Int, sopo: Array<Any>)
    protected abstract fun wrLog(ara: SocketAddress, key: Int, protocol: Int)    // 로그출력하는 방법을 구현하는 추상함수
    protected abstract fun wrErrLog(ara : SocketAddress , key: Int, code: Int)   // 에러로그 출력하는 방법을 구현하는 추상함수 (key : 유저 , code : 에러 유형)
    protected abstract fun usrKick(ara: SocketAddress)  // 유저에게 연결을 끊는다고 통보하는 함수 (보통, 이걸 받으면 앱을 강제 종료시킴)
    protected abstract fun qaddRoom()                                // qadd 함수를 실컷 구현하라고 그냥 넣어놓은 추상함수
}
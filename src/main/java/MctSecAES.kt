import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class MctSecAES(
    key : String, // 프로젝트마다 다른 이름으로
    iv : String,  // 프로젝트마다 다른 이름으로
    algorithm : String = "AES",    // 암호화 알고리즘
    transformation : String = "AES/CBC/PKCS5Padding",  // 암호화 알고리즘
    cs: Charset = Charsets.ISO_8859_1  // 암호를 문자로 저장할 경우 문자 형식
)
{
    private val msKey = SecretKeySpec(make16byte(key),algorithm)   // 키 문자열
    private val msIv = IvParameterSpec(make16byte(iv))                              // IV 문자열
    private val decCipher = Cipher.getInstance(transformation).apply { init(Cipher.DECRYPT_MODE, msKey, msIv) }   //복호화 사이퍼
    private val cs = cs

    //16바이트배열 만들기
    private fun make16byte(str: String) : ByteArray{
        return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(str.toByteArray()),16)
    }

    //서버를 켤 때마다 바뀌는 16바이트 배열
    private fun rand16byte() : ByteArray{ return Random(System.currentTimeMillis()).nextBytes(16) }

    //복호화
    @Synchronized
    fun dec(arr : ByteArray) : String{ return String(decCipher.doFinal(arr)) }
    // 복호화 (바이트 배열로)
    @Synchronized
    fun decArr(arr: ByteArray) : ByteArray{ return decCipher.doFinal(arr) }
    // 복호화 (String 으로 된 암호를 String 으로 복호화)
    @Synchronized
    fun decFromStr(str: String) : String{ return dec(str.toByteArray(cs)) }
    // 복호화 (String 으로 된 암호를 바이트배열로 복호화)
    fun decFromStrToArr(str : String) : ByteArray{ return decArr(str.toByteArray(cs)) }
}
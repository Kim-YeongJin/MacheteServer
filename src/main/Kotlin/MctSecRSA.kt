import java.nio.charset.Charset
import java.security.*
import javax.crypto.Cipher

class MctSecRSA(keySize : Int = 2048, cs: Charset = Charsets.ISO_8859_1  // 암호를 문자로 저장할 경우 문자 형식. ISO_8859_1 은 길이를 압축한 형식임.
){
    private val cs = cs

    val publKey: PublicKey
    private val priKey: PrivateKey
    private val decCipher : Cipher

    init{
        val keygen = KeyPairGenerator.getInstance("RSA")
        keygen.initialize(keySize);
        val keyPair = keygen.generateKeyPair()
        publKey = keyPair.public
        priKey = keyPair.private
        decCipher = Cipher.getInstance("RSA").apply { init(Cipher.DECRYPT_MODE,priKey) }//복호화 사이퍼
    }

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
    @Synchronized
    fun decFromStrToArr(str : String) : ByteArray{ return decArr(str.toByteArray(cs)) }
}
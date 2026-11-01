package cz.watch

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(secret: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest("watch:e2e:$secret".toByteArray())
        return SecretKeySpec(digest, "AES")
    }

    fun encrypt(secret: String, plain: String): String {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(TAG_BITS, iv))
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + enc, Base64.NO_WRAP)
    }

    fun decrypt(secret: String, b64: String): String? = try {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LEN)
        val enc = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(enc), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

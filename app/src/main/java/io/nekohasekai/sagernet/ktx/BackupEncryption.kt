package io.nekohasekai.sagernet.ktx

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Portable to Android 6: PBKDF2-HMAC-SHA1 + AES-256-GCM. The authenticated
// header fixes the KDF cost so an untrusted file cannot request unlimited work.
object BackupEncryption {
    private val magic = "NBKENC1\n".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val ITERATIONS = 1_300_000
    private val headerBytes = magic.size + SALT_BYTES + IV_BYTES

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    private fun cipher(mode: Int, password: CharArray, header: ByteArray): Cipher {
        val salt = header.copyOfRange(magic.size, magic.size + SALT_BYTES)
        val iv = header.copyOfRange(magic.size + SALT_BYTES, headerBytes)
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
                updateAAD(header)
            }
        } finally { key.fill(0) }
    }

    fun encrypt(bytes: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "Use at least 8 characters for the backup password" }
        require(bytes.size <= MAX_BACKUP_BYTES - headerBytes - TAG_BYTES) { "Backup exceeds size limit" }
        val header = ByteArray(headerBytes).also {
            SecureRandom().nextBytes(it)
            magic.copyInto(it)
        }
        return header + cipher(Cipher.ENCRYPT_MODE, password, header).doFinal(bytes)
    }

    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        require(isEncrypted(bytes) && bytes.size >= headerBytes + TAG_BYTES && bytes.size <= MAX_BACKUP_BYTES) { "Invalid encrypted backup" }
        val header = bytes.copyOfRange(0, headerBytes)
        return cipher(Cipher.DECRYPT_MODE, password, header).doFinal(bytes, headerBytes, bytes.size - headerBytes)
    }
}

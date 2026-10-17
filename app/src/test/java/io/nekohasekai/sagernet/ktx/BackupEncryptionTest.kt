package io.nekohasekai.sagernet.ktx

import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException

class BackupEncryptionTest {
    @Test fun roundTripAndFreshSalt() {
        val payload = "{\"profiles\": [\"secret\"]}".toByteArray()
        val password = "пароль 🔐 strong".toCharArray()
        val first = BackupEncryption.encrypt(payload, password)
        val second = BackupEncryption.encrypt(payload, password)
        assertTrue(BackupEncryption.isEncrypted(first))
        assertFalse(first.contentEquals(second))
        assertArrayEquals(payload, BackupEncryption.decrypt(first, password))
    }

    @Test fun rejectsWrongPasswordAndTampering() {
        val encrypted = BackupEncryption.encrypt("secret".toByteArray(), "password123".toCharArray())
        assertThrows(GeneralSecurityException::class.java) { BackupEncryption.decrypt(encrypted, "incorrect".toCharArray()) }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { BackupEncryption.decrypt(encrypted, "password123".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { BackupEncryption.decrypt(encrypted.copyOf(9), "password123".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { BackupEncryption.encrypt(byteArrayOf(1), "short".toCharArray()) }
    }
}

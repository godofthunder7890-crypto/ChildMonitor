package com.system.service.core

import java.io.File
import java.security.MessageDigest

object SHA256Helper {

    fun compute(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(65536).use { input ->
                val buf = ByteArray(65536)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { byte -> "%02x".format(byte) }
        } catch (_: Exception) {
            ""
        }
    }

    fun verify(file: File, expectedHash: String): Boolean {
        if (expectedHash.isBlank()) return true
        val actual = compute(file)
        return actual.isNotEmpty() && actual.equals(expectedHash.trim(), ignoreCase = true)
    }
}

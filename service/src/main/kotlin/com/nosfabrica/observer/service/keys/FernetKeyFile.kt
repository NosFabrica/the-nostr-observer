package com.nosfabrica.observer.service.keys

import com.vitorpamplona.quartz.utils.Log
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/**
 * brainstorm_server's Fernet key file (`/run/secrets/nsec_encryption_keys`): comma-separated
 * keys, tried in order ([MultiFernet]). The server writes and rotates it; every reader mounts it.
 *
 * It is read again when its modification time changes, and once more, whatever the time says,
 * when no key opens a token: a rotation writes `[new, old]` and then re-encrypts, and a rewrite
 * within the file system's time granularity would otherwise go unseen. A missing file is no keys:
 * every token is unreadable until it appears.
 */
class FernetKeyFile(
    val path: Path = Paths.get(DEFAULT_PATH),
) {
    private var keys = MultiFernet(emptyList())
    private var loadedAt: FileTime? = null
    private var loaded = false

    /** [token]'s plaintext, or null when no key opens it, even after reading the file again. */
    @Synchronized
    fun decrypt(token: String): ByteArray? {
        val modified = modifiedTime()
        if (!loaded || modified != loadedAt) reload(modified)
        keys.decrypt(token)?.let { return it }
        reload(modifiedTime())
        return keys.decrypt(token)
    }

    /** How many keys the file held when last read: for the startup log, never the keys. */
    @Synchronized
    fun keyCount(): Int {
        if (!loaded) reload(modifiedTime())
        return keys.keys.size
    }

    private fun modifiedTime(): FileTime? =
        try {
            Files.getLastModifiedTime(path)
        } catch (e: IOException) {
            null
        }

    private fun reload(modified: FileTime?) {
        val text =
            try {
                Files.readString(path)
            } catch (e: NoSuchFileException) {
                ""
            } catch (e: IOException) {
                // The class only: the path is ours, but an IO message is no place to look for a key.
                Log.w(TAG) { "could not read the key file: ${e.javaClass.simpleName}" }
                ""
            }
        keys = MultiFernet.parse(text) { i -> Log.w(TAG) { "key ${i + 1} in the key file is not a Fernet key; skipped" } }
        loadedAt = modified
        loaded = true
    }

    override fun toString() = "FernetKeyFile($path)"

    companion object {
        private const val TAG = "FernetKeyFile"

        /** brainstorm_server's `KEY_FILE_PATH`. */
        const val DEFAULT_PATH = "/run/secrets/nsec_encryption_keys"
    }
}

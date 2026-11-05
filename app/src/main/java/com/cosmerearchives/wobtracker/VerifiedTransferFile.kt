package com.cosmerearchives.wobtracker

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** Stage first and verify the provider's saved bytes before announcing completion. */
object VerifiedTransferFile {
    fun save(context: Context, uri: Uri, progress: (TransferProgress) -> Unit, write: (OutputStream) -> Int): Int {
        val staging = File.createTempFile("verified-transfer-", ".tmp", context.cacheDir)
        try {
            val count = staging.outputStream().use(write)
            val expected = MessageDigest.getInstance("SHA-256")
            staging.inputStream().use { input -> val buffer=ByteArray(65536); var done=0L; while(true){val n=input.read(buffer);if(n<0)break;expected.update(buffer,0,n);done+=n;progress(TransferProgress("Verifying staged file",done,staging.length(),"bytes"))} }
            context.contentResolver.openOutputStream(uri,"wt")?.use { output -> staging.inputStream().use { input -> val buffer=ByteArray(65536);var done=0L;while(true){val n=input.read(buffer);if(n<0)break;progress(TransferProgress("Saving file",done,staging.length(),"bytes"));output.write(buffer,0,n);done+=n} } } ?: error("Cannot open destination file")
            val actual=MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input -> val buffer=ByteArray(65536);var done=0L;while(true){val n=input.read(buffer);if(n<0)break;actual.update(buffer,0,n);done+=n;progress(TransferProgress("Verifying saved file",done,staging.length(),"bytes"))} } ?: error("Cannot verify saved file")
            require(expected.digest().contentEquals(actual.digest())) { "Saved file verification failed. Keep your previous backups and retry." }
            return count
        } finally { staging.delete() }
    }
}

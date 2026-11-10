package com.cosmerearchives.wobtracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Images are downsampled and copied to the local database, not linked to a temporary Gallery URI. */
private fun prepareJpeg(context: android.content.Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // inJustDecodeBounds intentionally makes decodeStream return null: check the
    // stream and decoded dimensions rather than interpreting null as failure.
    val boundsStream = resolver.openInputStream(uri) ?: error("Unable to open image")
    boundsStream.use { BitmapFactory.decodeStream(it, null, options) }
    require(options.outWidth > 0 && options.outHeight > 0) { "Not a supported image" }
    var sample = 1
    while (options.outWidth / sample > 1600 || options.outHeight / sample > 1600) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Unable to decode image")
    return try {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val bytes = out.toByteArray()
        require(bytes.size <= 2_000_000) { "The image is too large after resizing (2 MB maximum)." }
        bytes
    } finally { bitmap.recycle() }
}

/** Does not depend on review save status: the attachment is persisted immediately. */
@Composable
fun WobImageEditor(library: WobLibrary, entryId: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember(entryId) { mutableIntStateOf(0) }
    val photo = remember(entryId, revision) { library.imageFor(entryId) }
    var busy by remember(entryId) { mutableStateOf(false) }
    var message by remember(entryId) { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            message = ""
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = prepareJpeg(context, uri)
                        check(library.saveImage(entryId, "image/jpeg", bytes))
                    }
                }
                result.onSuccess {
                    revision++
                    message = "✓ Image attached and saved to this WoB."
                    onChanged()
                }.onFailure { message = "Image not saved: ${it.message ?: "Unknown error"}" }
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Image attachment", style = MaterialTheme.typography.titleSmall)
        if (photo != null) {
            val bitmap = remember(entryId, revision) {
                BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = "Attached image for this WoB",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    contentScale = ContentScale.Fit)
            }
            Text("Attached locally · included in backups and Archives transfers", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { pick.launch("image/*") }) {
                Text(if (photo == null) "Attach image" else "Replace image")
            }
            if (photo != null) {
                TextButton(enabled = !busy, onClick = {
                    if (library.deleteImage(entryId)) {
                        revision++
                        message = "Image removed."
                        onChanged()
                    }
                }) { Text("Remove") }
            }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

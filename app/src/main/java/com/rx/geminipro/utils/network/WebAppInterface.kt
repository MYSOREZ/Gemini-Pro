package com.rx.geminipro.utils.network

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import android.util.Base64
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import android.app.DownloadManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.webkit.MimeTypeMap

private const val TAG = "WebAppInterface"

class WebAppInterface(
    private val webViewRef: WeakReference<WebView>,
    private val getRetryUrl: () -> String
) {
    private val context: Context? get() = webViewRef.get()?.context

    @JavascriptInterface
    fun showToast(toast: String) {
        context?.let { ctx ->
            Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun downloadBlob(base64Data: String, filename: String) {
        Log.d(TAG, "downloadBlob called with filename: $filename")
        
        context?.let { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Remove data URL prefix if present
                    val cleanBase64 = if (base64Data.contains(",")) {
                        base64Data.substringAfter(",")
                    } else {
                        base64Data
                    }
                    
                    // Decode base64 data
                    val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    
                    // Generate unique filename if needed
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val finalFilename = if (filename.isBlank()) {
                        "gemini_download_$timestamp"
                    } else {
                        filename
                    }
                    
                    // Save to Downloads folder
                    saveToDownloads(ctx, bytes, finalFilename)
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(ctx, "Downloaded: $finalFilename", Toast.LENGTH_SHORT).show()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading blob", e)
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun downloadMedia(base64Data: String, fileType: String) {
        Log.d(TAG, "downloadMedia called with fileType: $fileType")
        
        context?.let { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Remove data URL prefix if present
                    val cleanBase64 = if (base64Data.contains(",")) {
                        base64Data.substringAfter(",")
                    } else {
                        base64Data
                    }
                    
                    // Decode base64 data
                    val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    
                    // Determine file extension from MIME type
                    val extension = when {
                        fileType.contains("image/png") -> "png"
                        fileType.contains("image/jpeg") || fileType.contains("image/jpg") -> "jpg"
                        fileType.contains("image/gif") -> "gif"
                        fileType.contains("image/webp") -> "webp"
                        fileType.contains("video/mp4") -> "mp4"
                        fileType.contains("video/webm") -> "webm"
                        fileType.contains("video/avi") -> "avi"
                        else -> "bin"
                    }
                    
                    // Generate filename
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val filename = "gemini_media_$timestamp.$extension"
                    
                    // Save to appropriate folder based on type
                    if (fileType.startsWith("image/")) {
                        saveImageToGallery(ctx, bytes, filename)
                    } else if (fileType.startsWith("video/")) {
                        saveVideoToGallery(ctx, bytes, filename)
                    } else {
                        saveToDownloads(ctx, bytes, filename)
                    }
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(ctx, "Downloaded: $filename", Toast.LENGTH_SHORT).show()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading media", e)
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun exportChatToPDF(htmlContent: String) {
        Log.d(TAG, "exportChatToPDF called")
        
        context?.let { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val filename = "gemini_chat_$timestamp.html"
                    
                    // Save HTML content
                    val bytes = htmlContent.toByteArray(Charsets.UTF_8)
                    saveToDownloads(ctx, bytes, filename)
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(ctx, "Chat exported: $filename", Toast.LENGTH_SHORT).show()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error exporting chat", e)
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun showContextWarning(messageCount: Int) {
        Log.d(TAG, "showContextWarning called with count: $messageCount")
        
        context?.let { ctx ->
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(
                    ctx,
                    "Warning: Chat has $messageCount messages. Consider starting a new chat for better performance.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveToDownloads(context: Context, bytes: ByteArray, filename: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(filename))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { output ->
                    output.write(bytes)
                }
            }
        } else {
            // Use legacy external storage for older versions
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            val file = File(downloadsDir, filename)
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
        }
    }

    private fun saveImageToGallery(context: Context, bytes: ByteArray, filename: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(filename))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Gemini")
            }
            
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { output ->
                    output.write(bytes)
                }
            }
        } else {
            val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Gemini")
            if (!picturesDir.exists()) {
                picturesDir.mkdirs()
            }
            
            val file = File(picturesDir, filename)
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
        }
    }

    private fun saveVideoToGallery(context: Context, bytes: ByteArray, filename: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(filename))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Gemini")
            }
            
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { output ->
                    output.write(bytes)
                }
            }
        } else {
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Gemini")
            if (!moviesDir.exists()) {
                moviesDir.mkdirs()
            }
            
            val file = File(moviesDir, filename)
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
        }
    }

    private fun getMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
    }
}
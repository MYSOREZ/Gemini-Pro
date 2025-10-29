package com.rx.geminipro.utils.network

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import android.content.Context

private const val TAG = "BlobDownloader"

class BlobDownloaderInterface {
    
    @JavascriptInterface
    fun processBlobData(dataUrl: String, filename: String): File? {
        return try {
            Log.d(TAG, "Processing blob data: $filename")
            
            val isBase64 = dataUrl.contains(";base64")
            val data = if (isBase64) {
                val base64EncodedData = dataUrl.substringAfter("base64,")
                Base64.decode(base64EncodedData, Base64.DEFAULT)
            } else {
                val decodedText = URLDecoder.decode(dataUrl.substringAfter(","), "UTF-8")
                decodedText.toByteArray(Charsets.UTF_8)
            }

            // Save to Downloads directory
            saveToDownloads(data, filename)
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save blob data to file.", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid Base64 string from data URL.", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error processing blob data.", e)
            null
        }
    }
    
    private fun saveToDownloads(data: ByteArray, filename: String): File? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && !downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            val file = File(downloadsDir, filename)
            FileOutputStream(file).use { fos ->
                fos.write(data)
            }
            
            Log.d(TAG, "File saved successfully to ${file.absolutePath}")
            file
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file to downloads", e)
            null
        }
    }
    
    /**
     * Enhanced blob processing with better MIME type detection
     */
    @JavascriptInterface
    fun processEnhancedBlobData(dataUrl: String, mimeType: String = ""): File? {
        return try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            
            // Determine file extension from MIME type or data URL
            val detectedMimeType = when {
                mimeType.isNotEmpty() -> mimeType
                dataUrl.startsWith("data:") -> {
                    val header = dataUrl.substringBefore(',')
                    header.substringAfter("data:").substringBefore(';')
                }
                else -> "application/octet-stream"
            }
            
            val extension = getExtensionFromMimeType(detectedMimeType)
            val dynamicName = "gemini_download_" + timestamp + "." + extension
            
            processBlobData(dataUrl, dynamicName)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in enhanced blob processing", e)
            null
        }
    }
    
    private fun getExtensionFromMimeType(mimeType: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return when {
            extension != null -> extension
            mimeType.contains("image/png") -> "png"
            mimeType.contains("image/jpeg") || mimeType.contains("image/jpg") -> "jpg"
            mimeType.contains("image/gif") -> "gif"
            mimeType.contains("image/webp") -> "webp"
            mimeType.contains("video/mp4") -> "mp4"
            mimeType.contains("video/webm") -> "webm"
            mimeType.contains("video/avi") -> "avi"
            mimeType.contains("application/pdf") -> "pdf"
            mimeType.contains("text/plain") -> "txt"
            mimeType.contains("text/html") -> "html"
            mimeType.contains("application/json") -> "json"
            else -> "bin"
        }
    }
    
    /**
     * Save data to appropriate media collection based on type
     */
    fun saveToMediaStore(context: Context, data: ByteArray, filename: String, mimeType: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                
                // Replace android.util.Pair destructuring with standard Pair
                val pair: Pair<android.net.Uri, String> = when {
                    mimeType.startsWith("image/") -> Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/Gemini")
                    mimeType.startsWith("video/") -> Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + "/Gemini")
                    mimeType.startsWith("audio/") -> Pair(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC + "/Gemini")
                    else -> Pair(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS)
                }
                val collection = pair.first
                val relativePath = pair.second
                
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                
                val uri = resolver.insert(collection, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { output ->
                        output.write(data)
                    }
                    Log.d(TAG, "File saved to MediaStore: $filename")
                    return true
                }
            } else {
                return saveToDownloads(data, filename) != null
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to MediaStore", e)
            false
        }
    }
}

package com.abhiank.offline

import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

fun copyStreamToFile(inputStream: InputStream, outputFile: File) {
    inputStream.use { input ->
        FileOutputStream(outputFile).use { output ->
            val buffer = ByteArray(4 * 1024)
            var byteCount: Int
            while (input.read(buffer).also { byteCount = it } >= 0) {
                output.write(buffer, 0, byteCount)
            }
            output.flush()
        }
    }
}

@Throws(IOException::class)
fun getFileFromAssets(context: Context, fileName: String): File =
    File(context.cacheDir, fileName).also { file ->
        if (!file.exists()) {
            file.outputStream().use { cache ->
                context.assets.open(fileName).use { inputStream ->
                    inputStream.copyTo(cache)
                }
            }
        }
    }

fun InputStream.readToString(): String {
    val reader = BufferedReader(InputStreamReader(this))
    val builder = StringBuilder()
    var line: String?
    while (reader.readLine().also { line = it } != null) {
        builder.append(line).append('\n')
    }
    return builder.toString()
}

fun Context.uriToCacheFile(uri: Uri, fileName: String): File {
    val cacheFile = File(cacheDir, fileName)
    contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(cacheFile).use { output ->
            input.copyTo(output)
        }
    }
    return cacheFile
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

package com.goanalyzer.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 野狐棋谱下载器：将 SGF 保存到本地文件系统（Downloads 文件夹）
 */
class FoxwqDownloader(private val context: Context) {

    /**
     * 保存野狐对局到本地 SGF 文件
     *
     * @param sgfContent SGF 内容
     * @param game       对局信息（用于生成文件名）
     * @return 保存结果，包含保存路径或错误信息
     */
    suspend fun downloadGame(sgfContent: String, game: FoxwqGame): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val fileName = buildFileName(game)
                val savedPath = saveToDownloads(fileName, sgfContent)
                Result.success(savedPath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 生成文件名
     * 格式：Foxwq_[黑方]vs[白方]_[结果]_[日期].sgf
     */
    private fun buildFileName(game: FoxwqGame): String {
        val blackName = sanitizeFileName(game.blackNick)
        val whiteName = sanitizeFileName(game.whiteNick)
        val result = when (game.winner) {
            1 -> "B"
            2 -> "W"
            else -> "D"
        }
        val date = game.startTime.replace(":", "-").replace(" ", "_")
        return "Foxwq_${blackName}_vs_${whiteName}_${result}_${date}.sgf"
    }

    /** 移除文件名中的非法字符 */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /**
     * 通过 MediaStore API 保存到 Downloads 文件夹
     * 兼容 Android 10+（分区存储）和 Android 9 以下
     */
    private fun saveToDownloads(fileName: String, content: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：使用 MediaStore
            saveViaMediaStore(fileName, content)
        } else {
            // Android 9 以下：直接写 Downloads 目录
            saveDirectly(fileName, content)
        }
    }

    private fun saveViaMediaStore(fileName: String, content: String): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-go-sgf")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("无法创建文件（存储空间不足或权限被拒）")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw Exception("无法写入文件")

        // 标记完成
        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        return "Downloads/$fileName"
    }

    private fun saveDirectly(fileName: String, content: String): String {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val file = File(downloadsDir, fileName)
        FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return file.absolutePath
    }
}

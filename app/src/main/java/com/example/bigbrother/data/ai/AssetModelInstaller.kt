package com.example.bigbrother.data.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Копирует .onnx из assets во внутреннее хранилище приложения.
 *
 * ONNX Runtime в C++ открывает модель по пути в файловой системе — напрямую
 * из Android assets он читать не умеет. Если файл уже скопирован и его размер
 * совпадает с размером asset, копирование пропускается.
 */
class AssetModelInstaller(private val context: Context) {

    fun install(assetPath: String, fileName: String): File {
        val targetDir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(targetDir, fileName)
        val assetSize = assetSizeOrNull(assetPath)

        if (target.exists() && (assetSize == null || target.length() == assetSize)) {
            return target
        }

        val temporary = File(targetDir, "$fileName.tmp")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE)
            }
        }

        if (temporary.renameTo(target)) {
            return target
        }

        // На 일부 ФС rename не срабатывает, если target уже есть — удаляем и повторяем.
        if (target.delete() && temporary.renameTo(target)) {
            return target
        }

        temporary.delete()
        throw IllegalStateException("Не удалось установить модель $fileName во внутреннее хранилище")
    }

    private fun assetSizeOrNull(assetPath: String): Long? = try {
        context.assets.openFd(assetPath).use { it.length }
    } catch (e: Exception) {
        // Сжатые assets не имеют/defer file descriptor — просто копируем.
        null
    }

}

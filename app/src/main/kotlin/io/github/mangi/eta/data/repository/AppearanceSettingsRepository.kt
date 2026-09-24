package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

object AppearanceSettingsRepository {
    /** 背景图在应用私有目录中的固定文件名；导入时覆盖，避免堆积历史文件。 */
    private const val BACKGROUND_IMAGE_NAME = "appearance_background_image"
    /** 背景图大小上限：8 MiB，防止导入超大原图撑爆内存或存储。 */
    private const val MAX_BACKGROUND_IMAGE_BYTES = 8L * 1024 * 1024

    fun settingsFlow(): Flow<AppearanceSettings> = SettingsDataStore.appearanceSettingsFlow()

    suspend fun settings(): AppearanceSettings = SettingsDataStore.settings().appearance

    suspend fun update(settings: AppearanceSettings) {
        SettingsDataStore.setAppearanceSettings(settings)
    }

    suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        SettingsDataStore.updateAppearanceSettings(transform)
    }

    private fun backgroundImageFile(context: Context): File =
        File(context.applicationContext.filesDir, BACKGROUND_IMAGE_NAME)

    /**
     * 把用户选择的图片拷贝到应用私有目录并启用为背景图。
     * 拷贝在私有目录内完成，不长期持有外部 URI 权限；超过大小上限直接拒绝。
     * @return 更新后的外观设置；失败时抛出 [IllegalArgumentException]。
     */
    suspend fun importBackgroundImage(context: Context, uri: Uri): AppearanceSettings =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val target = backgroundImageFile(appContext)
            val tmp = File(appContext.filesDir, "$BACKGROUND_IMAGE_NAME.importing")
            try {
                var copied = 0L
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            require(copied <= MAX_BACKGROUND_IMAGE_BYTES) { "背景图过大（上限 8 MB）" }
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IllegalArgumentException("无法读取所选图片")
                require(copied > 0L) { "所选图片为空" }
                if (target.exists() && !target.delete()) throw IllegalArgumentException("无法替换旧背景图")
                if (!tmp.renameTo(target)) throw IllegalArgumentException("无法保存背景图")
            } finally {
                tmp.delete()
            }
            SettingsDataStore.updateAppearanceSettings { current ->
                current.copy(backgroundImageEnabled = true, backgroundImagePath = target.absolutePath)
            }
            settings()
        }

    /** 关闭并删除已导入的背景图文件。 */
    suspend fun clearBackgroundImage(context: Context) {
        withContext(Dispatchers.IO) {
            backgroundImageFile(context.applicationContext).delete()
        }
        SettingsDataStore.updateAppearanceSettings { current ->
            current.copy(backgroundImageEnabled = false, backgroundImagePath = "")
        }
    }
}

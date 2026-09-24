package io.github.mangi.eta.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义背景图容器：启用且文件存在时，在内容底层绘制背景图与一层遮罩（保证前景可读），
 * 未启用时零开销直接渲染内容。图片解码放到 IO 线程，路径失效时静默回退到纯主题背景。
 */
@Composable
internal fun AppearanceBackground(
    content: @Composable () -> Unit,
) {
    val appearance = LocalAppearanceSettings.current
    val enabled = appearance.backgroundImageEnabled && appearance.backgroundImagePath.isNotBlank()

    if (!enabled) {
        content()
        return
    }

    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        appearance.backgroundImagePath,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(appearance.backgroundImagePath)
                if (!file.isFile) return@runCatching null
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val image = bitmap
    if (image == null) {
        // 尚未解码或路径失效：直接显示内容，绝不因背景图缺失挡住界面。
        content()
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithScrim(appearance.backgroundImageScrim),
        )
        content()
    }
}

private fun Modifier.drawWithScrim(scrim: Float): Modifier =
    drawBehind {
        if (scrim > 0f) {
            drawRect(color = Color.Black.copy(alpha = scrim.coerceIn(0f, 1f)))
        }
    }

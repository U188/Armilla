package io.github.mangi.eta.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AssistantAvatar(
    assistant: AssistantProfile?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(assistant?.id, assistant?.avatarFileName) {
        AssistantRepository.avatarBitmap(assistant?.avatarFileName)
    }
    // 用户未设头像时，内置助手回退到随包分发的固定头像资源。
    val builtinRes = remember(assistant?.id, assistant?.avatarFileName) {
        if (bitmap != null || assistant == null) null
        else builtinAvatarResource(assistant.id)
    }
    val builtinBitmap = remember(builtinRes, context) {
        builtinRes?.let { rasterizeDrawable(context, it) }
    }
    if (bitmap == null && builtinBitmap != null) {
        AssistantAvatarImage(imageBitmap = builtinBitmap, size = size, modifier = modifier)
    } else {
        AssistantAvatar(bitmap = bitmap, size = size, modifier = modifier)
    }
}

private fun builtinAvatarResource(assistantId: String): Int? = when (assistantId) {
    io.github.mangi.eta.data.model.AssistantPrompt.DEFAULT_ID -> R.drawable.assistant_avatar_default
    io.github.mangi.eta.data.model.AssistantPrompt.HACKER_ID -> R.drawable.assistant_avatar_hacker
    else -> null
}

@Composable
internal fun AssistantAvatar(
    bitmap: Bitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageBitmap = remember(bitmap, context) {
        bitmap
            ?.takeUnless { it.isRecycled }
            ?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            ?: rasterizeDefaultAvatar(context)
    }
    AssistantAvatarImage(imageBitmap = imageBitmap, size = size, modifier = modifier)
}

@Composable
private fun AssistantAvatarImage(
    imageBitmap: ImageBitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun rasterizeDefaultAvatar(context: Context): ImageBitmap? {
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_assistant_default)
        ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        ?: return null
    val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 128)
    return runCatching {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}

private fun rasterizeDrawable(context: Context, resId: Int): ImageBitmap? {
    val drawable = ContextCompat.getDrawable(context, resId) ?: return null
    val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 128)
    return runCatching {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}

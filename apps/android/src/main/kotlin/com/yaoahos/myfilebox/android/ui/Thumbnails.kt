package com.yaoahos.myfilebox.android.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 缩略图内存缓存：key = path + size，最多 120 张，避免列表滚动反复解码。 */
private object ThumbCache {
    private val lru = object : LruCache<String, ImageBitmap>(120) {}
    fun get(key: String): ImageBitmap? = lru.get(key)
    fun put(key: String, bitmap: ImageBitmap) {
        lru.put(key, bitmap)
    }
}

/**
 * 远程图片缩略图加载器：把远端文件下载到缓存后返回可解码的本地路径。
 * 由 AppRoot 注入 ViewModel 实现，列表本身不直接依赖网络层。
 */
typealias RemoteThumbLoader = suspend (FileEntry) -> String?

val LocalRemoteThumbLoader = staticCompositionLocalOf<RemoteThumbLoader?> { null }

/** 只有本地绝对路径且真实存在的图片 / 视频才能直接解码。 */
private fun localDecodable(file: FileEntry): Boolean {
    if (file.kind != FileKind.IMAGE && file.kind != FileKind.VIDEO) return false
    if (!file.path.startsWith("/")) return false
    return runCatching { File(file.path).isFile }.getOrDefault(false)
}

/** 用 inSampleSize 采样解码图片，避免把大图整张读进内存。 */
private fun decodeImage(path: String, size: Dp): ImageBitmap? = try {
    val target = (size.value * 2f).toInt().coerceAtLeast(96)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    if (maxDim > target) {
        while (maxDim / (sample * 2) >= target) sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
} catch (t: Throwable) {
    null
}

/** 取视频一帧作为缩略图。 */
private fun decodeVideo(path: String): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.getFrameAtTime()?.asImageBitmap()
    } catch (t: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 文件缩略图。
 * - IMAGE / VIDEO 且是本地文件：异步采样解码或取帧，视频叠加播放三角。
 * - 其它类型 / 远程文件 / 解码失败：回退到类型图标。
 * - [size] 同时作为显示尺寸、采样基准与缓存 key 的一部分；[modifier] 用于附加装饰。
 */
@Composable
fun FileThumb(file: FileEntry, size: Dp, modifier: Modifier = Modifier, dark: Boolean = false) {
    val loader = LocalRemoteThumbLoader.current
    val decodable = remember(file.path, file.kind) {
        file.kind == FileKind.IMAGE || file.kind == FileKind.VIDEO
    }
    if (!decodable) {
        // 直接画图标（useThumb = false），避免再次触发 FileThumb 造成递归。
        Box(Modifier.then(modifier)) {
            IconTile(file.kind, size.value.toInt(), useThumb = false)
        }
        return
    }

    // 清单 2：缩略图底板一律用类型浅彩，深色模式也不退化成灰色 surfaceVariant。
    val plate = fileTint(file.kind).first
    val shape = RoundedCornerShape((size.value * 0.14f).coerceIn(6f, 18f).dp)
    val cacheKey = "${file.path}@${size.value}"

    var bitmap by remember(cacheKey) { mutableStateOf(ThumbCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        val cached = ThumbCache.get(cacheKey)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        // 本地文件直接解码；远程图片 / 小体积视频通过注入的加载器取回缓存后解码，
        // 大视频或加载失败时回退到带播放角标的类型图标。
        val path = if (localDecodable(file)) file.path else loader?.invoke(file)
        if (path.isNullOrBlank()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            if (file.kind == FileKind.VIDEO) decodeVideo(path) else decodeImage(path, size)
        }
        if (loaded != null) {
            ThumbCache.put(cacheKey, loaded)
            bitmap = loaded
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .then(modifier)
            .clip(shape)
            .background(plate),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            if (file.kind == FileKind.VIDEO) {
                Box(
                    Modifier.matchParentSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // 彩色圆底 + 白色播放三角：角标本身也是彩色，不再是孤零零的白图标。
                    // 圆底跟随主题色，视频缩略图在换配色后不会再留一颗品牌蓝角标。
                    Box(
                        Modifier
                            .size((size.value * 0.42f).dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            "播放",
                            tint = Color.White,
                            modifier = Modifier.size((size.value * 0.28f).dp)
                        )
                    }
                }
            }
        } else {
            // 加载中 / 解码失败：直接画类型图标，复用外层底板，不再调用 IconTile。
            Icon(
                fileIcon(file.kind),
                null,
                // 清单 2：加载中 / 解码失败也用饱和彩色，深色模式不再退化成单色 primary。
                tint = fileTint(file.kind).second,
                modifier = Modifier.size((size.value * 0.45f).dp)
            )
        }
    }
}

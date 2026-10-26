package com.starveil.feature.user

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 海报上的可变文案：全部来自后台 `v1.user/shareInfo`。 */
internal data class PosterContent(
    val title: String,
    val description: String,
    val url: String,
)

/** 海报配色：跟随当前皮肤，只做很薄的一层染色。 */
internal data class PosterPalette(
    val accent: Int,
    val background: Int,
    val foreground: Int,
)

/**
 * 分享海报：**模板图固定，只有文案和二维码是变的**。
 *
 * 底图是 `drawable-nodpi/share_poster_template.jpg`（1080×1440 成品素材），
 * 运行时只做三件事：压一层皮肤色、在左下写标题/描述、右下贴二维码。
 * 二维码里的链接来自后台配置，客户端不拼地址。
 *
 * 图存到 `cacheDir/share/`，通过 `FileProvider` 授权给分享面板，不写外部存储、不要存储权限。
 */
internal object SharePoster {

    /**
     * 生成海报并返回可分享的 Uri；任何一步失败都返回 null，由调用方提示。
     *
     * 解码模板、Canvas 绘制、二维码编码与 PNG 压缩是几百毫秒级的重活，原来在
     * `LaunchedEffect`（主线程）里做，低端机会直接掉帧甚至 ANR。这里整体切到
     * [Dispatchers.IO]，调用方本来就是挂起上下文，接口形状不变。
     */
    suspend fun create(context: Context, content: PosterContent, palette: PosterPalette): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val poster = draw(context, content, palette)
                val dir = File(context.cacheDir, POSTER_DIR).apply { mkdirs() }
                val file = File(dir, POSTER_NAME)
                FileOutputStream(file).use { out ->
                    poster.compress(Bitmap.CompressFormat.PNG, POSTER_QUALITY, out)
                }
                // 压缩完立刻放掉 1080×1440 的像素内存，不等 GC
                poster.recycle()
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }

    /**
     * 拉起系统分享面板；返回是否真的拉起来了（`startActivity` 抛异常 = 没拉起来）。
     *
     * 平台限制：chooser 没有可靠的结果回调——`StartActivityForResult` 对 chooser
     * 只会立刻回 `RESULT_CANCELED`，`ACTION_SEND` 的接收方也不承诺回传"用户是否发出"。
     * 所以这里只能判定"面板是否打开"；发奖交给页面：拉起失败不发，拉起成功也只记一笔，
     * 等用户回到前台再确认（见 `UserCenterViewModel.onResumed`）。
     */
    fun share(context: Context, poster: Uri, url: String, title: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_IMAGE
            putExtra(Intent.EXTRA_STREAM, poster)
            if (url.isNotBlank()) putExtra(Intent.EXTRA_TEXT, url)
            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            // ClipData 会把读授权一起带过去；少了它系统分享面板取不到缩略图，接收方也读不了这个 Uri
            clipData = ClipData.newUri(context.contentResolver, POSTER_CLIP_LABEL, poster)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, SHARE_TITLE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }.isSuccess

    /**
     * 海报底图固定 [WIDTH]×[HEIGHT]：模板即便被抽样过也按整幅铺满，
     * 后面的绘制坐标就不用跟着模板尺寸缩放。
     */
    private fun draw(context: Context, content: PosterContent, palette: PosterPalette): Bitmap {
        val poster = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(poster)
        val template = loadTemplate(context)
        if (template != null) {
            canvas.drawBitmap(template, null, Rect(0, 0, WIDTH, HEIGHT), null)
            template.recycle()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        washAccent(canvas, paint, palette.accent)
        washScrim(canvas, paint)
        drawQr(canvas, paint, content.url)
        drawCaption(canvas, paint, content, palette.foreground)
        return poster
    }

    /** 皮肤色：只压很薄一层，模板本身的明暗不被盖掉。 */
    private fun washAccent(canvas: Canvas, paint: Paint, accent: Int) {
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            HEIGHT.toFloat(),
            withAlpha(accent, ACCENT_ALPHA_TOP),
            withAlpha(accent, ACCENT_ALPHA_BOTTOM),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null
    }

    /** 下沿补一层黑：保证文字与二维码的对比度，和模板本来就暗的下半部分无缝衔接。 */
    private fun washScrim(canvas: Canvas, paint: Paint) {
        val top = HEIGHT * SCRIM_START_RATIO
        paint.shader = LinearGradient(
            0f,
            top,
            0f,
            HEIGHT.toFloat(),
            withAlpha(SCRIM_COLOR, 0),
            withAlpha(SCRIM_COLOR, SCRIM_ALPHA),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, top, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null
    }

    /** 二维码贴在右下角，底下垫一块白色圆角板保证可扫。 */
    private fun drawQr(canvas: Canvas, paint: Paint, link: String) {
        val qr = qrBitmap(link) ?: return
        val left = WIDTH - PADDING - QR_SIZE
        val top = HEIGHT - PADDING - QR_SIZE
        paint.color = QR_PLATE
        canvas.drawRoundRect(
            RectF(
                left - QR_PADDING,
                top - QR_PADDING,
                left + QR_SIZE + QR_PADDING,
                top + QR_SIZE + QR_PADDING,
            ),
            QR_PLATE_RADIUS,
            QR_PLATE_RADIUS,
            paint,
        )
        canvas.drawBitmap(qr, left, top, null)
    }

    /** 左下角标题 + 一行描述，和右侧二维码同一水平带。 */
    private fun drawCaption(canvas: Canvas, paint: Paint, content: PosterContent, foreground: Int) {
        paint.color = foreground
        paint.setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, withAlpha(SHADOW_COLOR, SHADOW_ALPHA))
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = TITLE_SIZE
        val lines = titleLines(content.title)
        var baseline = TITLE_LAST_BASELINE - (lines.size - 1) * TITLE_LINE_HEIGHT
        lines.forEach { line ->
            canvas.drawText(line, PADDING, baseline, paint)
            baseline += TITLE_LINE_HEIGHT
        }
        paint.typeface = Typeface.DEFAULT
        paint.textSize = BODY_SIZE
        val desc = content.description.ifBlank { DEFAULT_DESC }.take(DESC_MAX_CHARS)
        canvas.drawText(desc, PADDING, DESC_BASELINE, paint)
        paint.clearShadowLayer()
    }

    /**
     * 模板是 nodpi 资源，正常情况下解码出来就是 1080×1440；仍然先读边界再按
     * [TEMPLATE_MAX_EDGE] 抽样：素材被换成超大图时，这里不会一把分配几十 MB。
     */
    private fun loadTemplate(context: Context): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, R.drawable.share_poster_template, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeResource(context.resources, R.drawable.share_poster_template, options)
    }.getOrNull()

    /** 最大边超过 [TEMPLATE_MAX_EDGE] 就按 2 的幂抽样；1080×1440 的模板返回 1。 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > TEMPLATE_MAX_EDGE) {
            sample *= 2
        }
        return sample
    }

    private fun titleLines(title: String): List<String> =
        title.ifBlank { DEFAULT_TITLE }.chunked(TITLE_LINE_CHARS).take(TITLE_MAX_LINES)

    private fun qrBitmap(link: String): Bitmap? {
        if (link.isBlank()) return null
        return runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to QR_MARGIN, EncodeHintType.CHARACTER_SET to "UTF-8")
            val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
            val pixels = IntArray(QR_SIZE * QR_SIZE)
            for (y in 0 until QR_SIZE) {
                for (x in 0 until QR_SIZE) {
                    pixels[y * QR_SIZE + x] = if (matrix[x, y]) QR_DARK else QR_LIGHT
                }
            }
            Bitmap.createBitmap(pixels, QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private const val POSTER_DIR = "share"
    private const val POSTER_NAME = "share_poster.png"
    private const val POSTER_QUALITY = 100
    private const val MIME_IMAGE = "image/png"
    private const val SHARE_TITLE = "分享到"
    private const val POSTER_CLIP_LABEL = "分享海报"

    private const val WIDTH = 1080
    private const val HEIGHT = 1440

    /** 模板解码后的最大边：素材换成超大图时先抽样，避免一次分配几十 MB。 */
    private const val TEMPLATE_MAX_EDGE = 1440
    private const val PADDING = 76f

    private const val TITLE_SIZE = 56f
    private const val TITLE_LINE_CHARS = 8
    private const val TITLE_MAX_LINES = 2
    private const val TITLE_LINE_HEIGHT = 66f
    private const val TITLE_LAST_BASELINE = 1256f
    private const val BODY_SIZE = 30f
    private const val DESC_BASELINE = 1322f
    private const val DESC_MAX_CHARS = 12

    private const val QR_SIZE = 340
    private const val QR_PADDING = 24f
    private const val QR_PLATE_RADIUS = 24f
    private const val QR_PLATE = 0xFFFFFFFF.toInt()
    private const val QR_MARGIN = 1
    private const val QR_DARK = -0x1000000
    private const val QR_LIGHT = -0x1

    private const val ACCENT_ALPHA_TOP = 0x0A
    private const val ACCENT_ALPHA_BOTTOM = 0x22

    private const val SCRIM_START_RATIO = 0.58f
    private const val SCRIM_COLOR = -0x1000000
    private const val SCRIM_ALPHA = 0xCC

    private const val SHADOW_COLOR = -0x1000000
    private const val SHADOW_ALPHA = 0xB4
    private const val SHADOW_RADIUS = 14f
    private const val SHADOW_DY = 5f

    private const val DEFAULT_TITLE = "星幕影视"
    private const val DEFAULT_DESC = "好片都在这儿"
}

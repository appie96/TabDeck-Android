package com.tabdeck

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ───────────── 색 (PC 프로그램과 같은 밝은 민트 테마) ─────────────
private val Bg = Color(0xFFEEF4F2)
private val CardC = Color.White
private val Ink = Color(0xFF1F2A44)      // 짙은 남색 글자
private val Sub = Color(0xFF8B95A7)      // 보조 회색 글자
private val Mint = Color(0xFF8FCFC0)
private val MintD = Color(0xFF4FA898)
private val MintL = Color(0xFFE2F3EF)
private val Line = Color(0xFFE2EBE8)
private val Danger = Color(0xFFE46F78)

// ───────────── 상호 인증: 비밀 키 자체는 보내지 않고, 키로 계산한 값만 주고받음 ─────────────
// PC의 auth_proof()와 똑같이 계산해야 함: HMAC-SHA256(key, "역할|PC난수|앱난수") → 16진수 소문자
object DeckAuth {
    private val random = SecureRandom()

    fun proof(key: String, role: String, serverNonce: String, clientNonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$role|$serverNonce|$clientNonce".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun newNonce(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // 걸리는 시간으로 정답을 추측할 수 없게 끝까지 비교
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

private const val MAX_ICON_B64 = 1_500_000   // 버튼 이미지 base64 최대 길이 (약 1.1MB). PC는 256px만 보냄
private const val MAX_ICON_SIDE = 1024       // 이보다 큰 그림은 무시 (메모리 부족으로 앱이 꺼지는 것 방지)
private const val MAX_BG_BYTES = 40L * 1024 * 1024   // 내 배경 파일 최대 40MB
private const val MAX_BG_SIDE = 1440                 // 배경은 화면 긴 변(최대 1440px)에 맞춰 줄여서 풀어 둠 (메모리 절약)

// 내 배경의 위치·확대. cx·cy = 화면 가운데에 올 그림 위치(0~1), zoom 1 = 화면을 꽉 채움, 1보다 작으면 '전체 보기' 쪽
data class BgCrop(val cx: Float = 0.5f, val cy: Float = 0.5f, val zoom: Float = 1f)

object BgMath {
    const val MAX_ZOOM = 4f
    // 꽉 채우는 배율 × zoom = 실제 배율 (그림 1px이 화면 몇 px인지)
    fun scale(zoom: Float, w: Float, h: Float, iw: Float, ih: Float) = max(w / iw, h / ih) * zoom
    // 가장 작게 줄였을 때 = 그림 전체가 화면 안에 다 들어옴
    fun minZoom(w: Float, h: Float, iw: Float, ih: Float) = min(w / iw, h / ih) / max(w / iw, h / ih)
    // 범위 정리: 그림이 화면보다 큰 쪽은 빈 곳이 안 보이게 막고, 작은 쪽은 가운데로
    fun clamp(c: BgCrop, w: Float, h: Float, iw: Float, ih: Float): BgCrop {
        val z = c.zoom.coerceIn(minZoom(w, h, iw, ih), MAX_ZOOM)
        val s = scale(z, w, h, iw, ih)
        fun axis(v: Float, view: Float, img: Float): Float {
            val half = view / (2 * s * img)            // 화면 절반이 그림의 몇 %인지
            return if (half >= 0.5f) 0.5f else v.coerceIn(half, 1 - half)
        }
        return BgCrop(axis(c.cx, w, iw), axis(c.cy, h, ih), z)
    }
    // 화면의 점(x, y)이 그림의 어디(0~1)인지. 그림 밖이면 0~1을 벗어남
    fun toImage(x: Float, y: Float, c: BgCrop, w: Float, h: Float, iw: Float, ih: Float): Pair<Float, Float> {
        val s = scale(c.zoom, w, h, iw, ih)
        return Pair(c.cx + (x - w / 2) / (s * iw), c.cy + (y - h / 2) / (s * ih))
    }
    // 화면 가로 띠(y0~y1, 화면 높이 비율)의 평균 밝기(0 어두움 ~ 1 밝음). px(u, v)는 그림 위 위치의 색(0xAARRGGBB)
    fun bandLuma(c: BgCrop, w: Float, h: Float, iw: Float, ih: Float, y0: Float, y1: Float,
                 outside: Float, px: (Float, Float) -> Int): Float {
        var sum = 0f; var n = 0
        for (j in 0 until 3) for (i in 0 until 12) {
            val (u, v) = toImage(w * (i + 0.5f) / 12, h * (y0 + (y1 - y0) * (j + 0.5f) / 3), c, w, h, iw, ih)
            sum += if (u < 0f || u > 1f || v < 0f || v > 1f) outside else {
                val p = px(u, v)
                (0.2126f * ((p shr 16) and 255) + 0.7152f * ((p shr 8) and 255) + 0.0722f * (p and 255)) / 255f
            }
            n++
        }
        return sum / n
    }
}

// PC에서 받은 버튼 하나의 정보
data class DeckButton(
    val id: String, val label: String,
    val color: Color, val text: Color,
    val mode: String,            // "image" = 이미지가 꽉 참, "glyph" = 가운데 아이콘, "none" = 글자만
    val icon: ImageBitmap?,
    val shade: Color             // 그림자·빛 색 (버튼 색, 이미지면 이미지 평균색)
)

// 색을 어둡게 (f < 1)
private fun Color.dim(f: Float) = Color(red * f, green * f, blue * f, alpha)

// ───────────── 화면 꾸미기 설정값 ─────────────
// 배경 움직임: 0 멈춤 / 1 천천히(절전, 초당 10번) / 2 부드럽게(매 프레임)
// 누를 때 효과: 아래 목록. 이름은 설정 화면에 그대로 보임
private val FX_LIST = listOf("sparkle" to "반짝", "glow" to "빛 번짐", "ripple" to "물결", "bounce" to "통통", "none" to "없음")

// ───────────── 효과음: 코드로 만든 짧은 '톡' 소리 ─────────────
class Ticker {
    private val rate = 44100

    private fun build(notes: List<Triple<Double, Int, Int>>): AudioTrack? = try {
        val total = notes.maxOf { it.second + it.third } + 50
        val n = rate * total / 1000
        val buf = DoubleArray(n)
        for ((freq, startMs, durMs) in notes) {
            val s0 = rate * startMs / 1000
            val len = rate * durMs / 1000
            for (i in 0 until len) {
                val t = i.toDouble() / rate
                val env = min(1.0, i / (rate * 0.004)) * exp(-t * 26)
                buf[s0 + i] += (sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t)) * env * 0.22
            }
        }
        val pcm = ShortArray(n) { (buf[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build().apply { write(pcm, 0, n) }
    } catch (e: Exception) { null }

    private val tick by lazy { build(listOf(Triple(1046.0, 0, 70))) }
    private val fail by lazy { build(listOf(Triple(330.0, 0, 120), Triple(262.0, 90, 160))) }

    fun play(kind: String) {
        val t = (if (kind == "fail") fail else tick) ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) { }
    }

    fun release() { try { tick?.release(); fail?.release() } catch (e: Exception) { } }
}

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ticker = Ticker()

    private val status = mutableStateOf("PC 찾는 중...")
    private val buttons = mutableStateListOf<DeckButton>()
    private val results = mutableStateMapOf<String, Pair<Boolean, Long>>()   // 버튼별 실행 결과 (성공여부, 시각)
    private val needKey = mutableStateOf(false)
    @Volatile private var paused = false     // PC가 키를 거절하면 새 키를 넣을 때까지 재접속을 멈춤
    private val soundOn = mutableStateOf(true)
    private val motion = mutableIntStateOf(1)          // 배경 움직임 (처음엔 '천천히')
    private val glass = mutableStateOf(true)           // 유리 효과
    private val fx = mutableStateOf("sparkle")         // 누를 때 효과
    private val bgMode = mutableStateOf("basic")       // 배경: basic(기본 빛 덩어리) / custom(내 사진·GIF)
    private val bgVer = mutableIntStateOf(0)           // 새 배경 파일을 고를 때마다 1씩 올려서 다시 읽게 함
    private val veil = mutableIntStateOf(1)            // 내 배경 위 가림막: 0 없음 / 1 살짝 / 2 진하게
    private val bgNote = mutableStateOf<String?>(null) // 배경 고르기 실패 안내
    private val onScreen = mutableStateOf(true)        // 앱이 화면에 보이는지 (뒤로 가면 GIF 재생 멈춤)
    private fun bgFile() = File(filesDir, "bg_custom")
    private val bgCrop = mutableStateOf(BgCrop())       // 내 배경 위치·확대
    private val adjusting = mutableStateOf(false)      // 배경 위치 조정 중인지
    private val bgThumb = mutableStateOf<Bitmap?>(null) // 밝기 계산용 작은 그림 (첫 장면)
    private val bgImg = mutableStateOf(Pair(0f, 0f))   // 풀어 둔 배경 그림 크기
    private val rootSize = mutableStateOf(IntSize.Zero) // 화면 크기

    private fun saveCrop(prefs: SharedPreferences, c: BgCrop) {
        prefs.edit().putFloat("bg_cx", c.cx).putFloat("bg_cy", c.cy).putFloat("bg_zoom", c.zoom).apply()
    }

    override fun onStart() { super.onStart(); onScreen.value = true }
    override fun onStop() { super.onStop(); onScreen.value = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 밝은 바탕이니까 위·아래 시스템 바 아이콘을 어두운 색으로
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 화면 꺼짐 방지
        if (Build.VERSION.SDK_INT >= 37) {
            requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 1)   // 같은 Wi-Fi 기기 접속 권한
        }
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        needKey.value = prefs.getString("key", null) == null
        soundOn.value = prefs.getBoolean("sound", true)        // 효과음: 처음엔 켜짐, 끄면 저장돼서 유지
        motion.intValue = prefs.getInt("motion", 1).coerceIn(0, 2)
        glass.value = prefs.getBoolean("glass", true)
        fx.value = prefs.getString("fx", "sparkle")?.takeIf { k -> FX_LIST.any { it.first == k } } ?: "sparkle"
        bgMode.value = if (prefs.getString("bg", "basic") == "custom" && bgFile().exists()) "custom" else "basic"
        veil.intValue = prefs.getInt("veil", 1).coerceIn(0, 2)
        bgCrop.value = BgCrop(prefs.getFloat("bg_cx", 0.5f).coerceIn(0f, 1f), prefs.getFloat("bg_cy", 0.5f).coerceIn(0f, 1f),
            prefs.getFloat("bg_zoom", 1f).coerceIn(0.05f, BgMath.MAX_ZOOM))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = MintD, secondary = Mint, background = Bg, surface = CardC)) {
                DeckScreen(prefs)
            }
        }
        scope.launch { connectLoop(prefs) }
    }

    // PC가 뿌리는 "DECK:포트" 신호를 듣고 IP를 찾음
    private fun discover(): String? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("deck").apply { acquire() }
        return try {
            DatagramSocket(8766).use { s ->
                s.soTimeout = 4000
                val p = DatagramPacket(ByteArray(64), 64)
                s.receive(p)
                // 형식이 정확히 "DECK:숫자"인 신호만 받아들임
                val text = String(p.data, 0, p.length)
                val port = text.removePrefix("DECK:").toIntOrNull()?.takeIf { text.startsWith("DECK:") && it in 1..65535 }
                if (port == null) null else "${p.address.hostAddress}:$port"
            }
        } catch (e: Exception) { null } finally { lock.release() }
    }

    private fun decodeIcon(b64: String): ImageBitmap? {
        if (b64.isEmpty() || b64.length > MAX_ICON_B64) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            // 먼저 크기만 읽어 보고, 너무 큰 그림이면 아예 풀지 않음
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..MAX_ICON_SIDE || bounds.outHeight !in 1..MAX_ICON_SIDE) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: Exception) { null }
    }

    // 이미지 전체의 평균색 (1×1로 줄여서 그 한 점의 색)
    private fun averageColor(img: ImageBitmap): Color = try {
        val one = android.graphics.Bitmap.createScaledBitmap(img.asAndroidBitmap(), 1, 1, true)
        Color(one.getPixel(0, 0))
    } catch (e: Exception) { Ink }

    private fun parseColor(hex: String, fallback: Color): Color =
        try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { fallback }

    // ───────────── 내 배경 (사진·GIF·움직이는 WebP) ─────────────
    // 고른 파일을 앱 전용 폴더에 복사해 둠 (원본을 지우거나 옮겨도 배경은 유지)
    private fun importBackground(uri: Uri, prefs: SharedPreferences) {
        scope.launch {
            val tmp = File(filesDir, "bg_custom.tmp")
            val note = try {
                val opened = contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        var ok = true
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BG_BYTES) { ok = false; break }   // 너무 큰 파일은 중간에 그만 받음
                            out.write(buf, 0, n)
                        }
                        ok
                    }
                }
                when {
                    opened == null -> "파일을 열 수 없어"
                    !opened -> "파일이 너무 커 (40MB까지 돼)"
                    loadBackground(tmp, 64) == null -> "이 파일은 그림으로 읽을 수 없어"
                    !tmp.renameTo(bgFile()) -> "파일을 저장하지 못했어"
                    else -> null
                }
            } catch (e: Exception) { "파일을 읽다가 실패했어" }
            tmp.delete()
            runOnUiThread {
                bgNote.value = note
                if (note == null) {
                    bgVer.intValue++
                    bgMode.value = "custom"
                    prefs.edit().putString("bg", "custom").apply()
                    bgCrop.value = BgCrop()
                    saveCrop(prefs, BgCrop())
                    adjusting.value = true                       // 고르자마자 위치 조정 화면 (PC 이미지 자르기와 같은 흐름)
                }
            }
        }
    }

    // 배경 파일을 화면 크기에 맞춰 풀기. GIF·움직이는 WebP면 움직이는 그림(AnimatedImageDrawable)이 나옴
    private fun loadBackground(f: File, maxSide: Int): Drawable? = try {
        if (!f.exists()) null
        else if (Build.VERSION.SDK_INT >= 28) {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val s = maxSide.toFloat() / max(w, h)
                if (s < 1f) dec.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
            if (d is AnimatedImageDrawable) d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE   // 한 번만 도는 GIF도 계속 반복
            d
        } else {
            // Android 8(API 26·27)은 움직이는 그림을 기본 기능으로 못 풀어서 첫 장면만 보여 줌
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { BitmapDrawable(resources, it) }
        }
    } catch (e: Exception) { null }

    // 밝기 계산용으로 첫 장면만 아주 작게(긴 변 48px) 풀기. 소프트웨어 메모리라 픽셀을 읽을 수 있음
    private fun loadThumb(f: File): Bitmap? = try {
        if (!f.exists()) null
        else if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val k = 48f / max(w, h)
                if (k < 1f) dec.setTargetSize(max(1, (w * k).toInt()), max(1, (h * k).toInt()))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > 96) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) { null }

    // 배경의 위·아래 띠가 어두운지 (어두우면 시계·배터리 아이콘과 제목을 흰색으로)
    private fun darkBands(): Pair<Boolean, Boolean> {
        val t = bgThumb.value ?: return Pair(false, false)
        val sz = rootSize.value
        if (bgMode.value != "custom" || sz.width == 0 || t.width == 0) return Pair(false, false)
        val w = sz.width.toFloat(); val h = sz.height.toFloat()
        val tw = t.width.toFloat(); val th = t.height.toFloat()
        val c = BgMath.clamp(bgCrop.value, w, h, tw, th)
        val a = when (veil.intValue) { 0 -> 0f; 1 -> 0.3f; else -> 0.55f }     // 가림막만큼 밝아짐
        val px = { u: Float, v: Float ->
            t.getPixel((u * (t.width - 1)).toInt().coerceIn(0, t.width - 1), (v * (t.height - 1)).toInt().coerceIn(0, t.height - 1))
        }
        fun dark(y0: Float, y1: Float) =
            BgMath.bandLuma(c, w, h, tw, th, y0, y1, 0.94f, px) * (1 - a) + 0.95f * a < 0.55f
        return Pair(dark(0f, 0.09f), dark(0.96f, 1f))
    }

    private fun playBackground(d: Drawable, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) { if (on) d.start() else d.stop() }
    }

    // 연결이 끊기면 계속 다시 시도하는 루프
    private suspend fun connectLoop(prefs: SharedPreferences) {
        while (true) {
            val key = prefs.getString("key", null)
            if (key == null || ws != null || paused) { delay(1000); continue }
            val manual = prefs.getString("manual", null)?.takeIf { it.isNotBlank() }?.let { "$it:8765" }
            val addr = manual ?: discover() ?: prefs.getString("addr", null)
            if (addr == null) { delay(2000); continue }
            prefs.edit().putString("addr", addr).apply()
            val req = Request.Builder().url("ws://$addr").build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                // 인증 상태 (연결마다 새로)
                private var serverNonce: String? = null
                private val clientNonce = DeckAuth.newNonce()
                private var trusted = false          // PC가 같은 키를 가진 진짜 PC인지 확인했는지

                override fun onMessage(w: WebSocket, text: String) {
                    val m = try { JSONObject(text) } catch (e: Exception) { return }
                    val type = m.optString("type")
                    if (!trusted) {
                        // 확인 전에는 인증 메시지와 거절 알림만 받음 (가짜 PC가 보낸 버튼·이미지는 무시)
                        when (type) {
                            "challenge" -> {
                                val n = m.optString("nonce")
                                if (n.length !in 16..128 || serverNonce != null) { w.close(1000, null); return }
                                serverNonce = n
                                w.send(JSONObject().put("type", "hello").put("cnonce", clientNonce)
                                    .put("proof", DeckAuth.proof(key, "app", n, clientNonce)).toString())
                            }
                            "welcome" -> {
                                val n = serverNonce ?: run { w.close(1000, null); return }
                                if (DeckAuth.same(m.optString("proof"), DeckAuth.proof(key, "pc", n, clientNonce))) {
                                    trusted = true
                                } else {
                                    runOnUiThread { status.value = "PC 확인 실패: 같은 키를 가진 TabDeck PC가 아니야" }
                                    w.close(1000, null)
                                }
                            }
                            "error" -> pauseForKey(m.optString("msg", "PC가 연결을 거절했어"))
                        }
                        return
                    }
                    when (type) {
                        "buttons" -> {
                            // 이미지 해석은 무거우니까 여기(백그라운드)서 미리 해 두고 화면에 넘김
                            val arr = m.getJSONArray("items")
                            val list = (0 until arr.length()).map { i ->
                                val o = arr.getJSONObject(i)
                                val icon = decodeIcon(o.optString("icon", ""))
                                val mode = o.optString("mode", "none").let { if (icon == null) "none" else it }
                                val color = parseColor(o.optString("color", "#ffffff"), CardC)
                                DeckButton(
                                    o.getString("id"), o.getString("label"), color,
                                    parseColor(o.optString("text", "#1f2a44"), Ink),
                                    mode, icon,
                                    if (mode == "image" && icon != null) averageColor(icon) else color
                                )
                            }
                            runOnUiThread {
                                buttons.clear(); buttons.addAll(list)
                                status.value = "연결됨"
                            }
                        }
                        "result" -> {
                            val id = m.getString("id")
                            val ok = m.getBoolean("ok")
                            runOnUiThread {
                                results[id] = Pair(ok, System.nanoTime())
                                if (!ok && soundOn.value) ticker.play("fail")
                            }
                        }
                        "error" -> pauseForKey(m.optString("msg", "PC가 연결을 끊었어"))
                    }
                }

                // 지금 쓰는 연결이 끊겼을 때만 다시 연결 (IP를 바꾸면서 버린 예전 연결은 무시)
                override fun onClosed(w: WebSocket, c: Int, r: String) {
                    if (ws === w) { ws = null; status.value = "연결 끊김, 재시도 중" }
                }
                override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                    if (ws === w) { ws = null; status.value = "실패: ${t.message}" }
                }
            })
            delay(3000)
        }
    }

    // PC가 키를 거절함: 저장된 키는 지우지 않고(가짜 PC가 지우게 하는 걸 막음) 새 키 입력칸만 띄움
    private fun pauseForKey(msg: String) {
        paused = true
        runOnUiThread {
            status.value = msg
            needKey.value = true
        }
    }

    private fun press(id: String) {
        if (soundOn.value) ticker.play("tick")
        ws?.send(JSONObject().put("type", "press").put("id", id).toString())
    }

    // ───────────── 화면 ─────────────
    @Composable
    fun DeckScreen(prefs: SharedPreferences) {
        val connected = status.value == "연결됨"
        var settingsOpen by remember { mutableStateOf(false) }
        // 배경 파일 고르기 창. 설정 카드는 연결되면 닫힐 수 있어서, 결과를 받는 쪽은 항상 떠 있는 여기에 둠
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) importBackground(uri, prefs)
        }
        val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

        // 배경 위·아래가 어두우면 상단 시계·배터리와 하단 바 아이콘을 흰색으로
        val dark = darkBands()
        LaunchedEffect(dark) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark.first
                isAppearanceLightNavigationBars = !dark.second
            }
        }

        Box(Modifier.fillMaxSize().onSizeChanged { rootSize.value = it }) {
        if (bgMode.value == "custom") {
            CustomBackdrop(motion.intValue)
            // 가림막: 사진 위에 배경색을 살짝 덮어서 버튼·글자가 잘 보이게
            if (veil.intValue > 0) Box(Modifier.fillMaxSize().background(Bg.copy(alpha = if (veil.intValue == 1) 0.3f else 0.55f)))
        } else Backdrop(motion.intValue)
        if (adjusting.value && bgMode.value == "custom") { AdjustOverlay(prefs); return@Box }
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // 위쪽: 제목 + 연결 상태 + 설정 버튼
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = if (dark.first) Color.White else Ink)) { append("Tab") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Light, color = if (dark.first) Color(0xFFD4F0E9) else MintD)) { append("Deck") }
                }, fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                StatusPill(connected)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(if (settingsOpen) MintL else cardBg())
                        .border(1.dp, cardLine(), CircleShape)
                        .clickable { settingsOpen = !settingsOpen },
                    contentAlignment = Alignment.Center
                ) { Text("⚙", fontSize = 18.sp, color = if (settingsOpen) MintD else Ink) }
            }

            // 설정 카드: 설정 버튼을 눌렀거나, 아직 연결 전이면 보임
            AnimatedVisibility(settingsOpen || needKey.value || !connected) {
                SettingsCard(prefs, pick)
            }

            if (connected && buttons.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("PC 프로그램에서 '버튼 추가'를 눌러 줘", color = Sub, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),          // 화면 폭에 맞춰 칸 수 자동 결정
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(buttons, key = { it.id }) { b ->
                        DeckTile(b, results[b.id]) { press(b.id) }
                    }
                }
            }
        }
        }
    }

    // 카드 바탕: 유리 효과면 반투명 흰색 (실시간 블러 없이 '가짜 유리' → 가벼움)
    private fun cardBg() = if (glass.value) Color.White.copy(alpha = 0.62f) else CardC
    private fun cardLine() = if (glass.value) Color.White.copy(alpha = 0.9f) else Line

    // ───────────── 배경: 은은한 색 덩어리 3개가 천천히 떠다님 ─────────────
    // 블러 계산 대신 '가운데 진하고 가장자리 투명한 원'으로 그려서 모든 기기에서 가볍게 돌아감
    @Composable
    fun Backdrop(mode: Int) {
        var t by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(mode) {
            when (mode) {
                1 -> while (true) {                        // 천천히: 초당 10번만 다시 그림
                    withFrameNanos { }                     // 화면이 꺼져 있거나 앱이 뒤에 있으면 여기서 멈춤
                    delay(100)
                    t += 0.1f
                }
                2 -> {                                     // 부드럽게: 화면 갱신마다 (60~120번/초)
                    var last = 0L
                    while (true) withFrameNanos { n ->
                        if (last != 0L) t += (n - last) / 1_000_000_000f
                        last = n
                    }
                }
            }
        }
        val blobs = remember {
            listOf(  // 색, 기준 위치(가로·세로 비율), 크기(긴 변 대비), 움직임 속도
                listOf(0xFF8FCFC0, 0.15f, 0.12f, 0.55f, 0.11f, 0.08f),
                listOf(0xFFF6C1C8, 0.90f, 0.40f, 0.45f, 0.09f, 0.12f),
                listOf(0xFFBCD7F2, 0.35f, 0.95f, 0.55f, 0.07f, 0.10f),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(Color(0xFFF4F9F7), Color(0xFFEAF3F0), Color(0xFFE1ECE9))))
            val big = max(size.width, size.height)
            for (bl in blobs) {
                val col = Color((bl[0] as Long).toInt())
                val bx = bl[1] as Float; val by = bl[2] as Float; val r = big * (bl[3] as Float)
                val sx = bl[4] as Float; val sy = bl[5] as Float
                val c = Offset(
                    size.width * (bx + 0.1f * sin(t * sx * 2.1f)),
                    size.height * (by + 0.08f * cos(t * sy * 2.1f))
                )
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.6f), col.copy(alpha = 0f)), c, r), r, c)
            }
        }
    }

    // ───────────── 내 배경: 사진은 그대로, GIF는 반복 재생 (화면 가득, 가운데 기준으로 잘림) ─────────────
    @Composable
    fun CustomBackdrop(mode: Int) {
        val ver = bgVer.intValue
        val side = remember {
            val m = resources.displayMetrics
            min(MAX_BG_SIDE, max(m.widthPixels, m.heightPixels))
        }
        val drawable by produceState<Drawable?>(null, ver) {
            val (d, thumb) = withContext(Dispatchers.IO) { Pair(loadBackground(bgFile(), side), loadThumb(bgFile())) }
            bgThumb.value = thumb
            if (d != null) bgImg.value = Pair(d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            value = d
        }
        val d = drawable
        if (d == null) { Backdrop(0); return }                 // 읽는 중이거나 실패하면 기본 배경(멈춤)
        val play = mode != 0 && onScreen.value                 // '멈춤'이거나 앱이 뒤에 있으면 재생 멈춤
        val sz = rootSize.value
        val (iw, ih) = bgImg.value
        val w = sz.width.toFloat(); val h = sz.height.toFloat()
        val c = if (w > 0 && iw > 0) BgMath.clamp(bgCrop.value, w, h, iw, ih) else BgCrop()
        if (c.zoom < 1f) Backdrop(0)                          // 전체 보기 쪽으로 줄이면 남는 곳은 기본 배경
        AndroidView(
            factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.MATRIX } },
            update = { v ->
                if (v.drawable !== d) v.setImageDrawable(d)
                if (w > 0 && iw > 0) {
                    val s = BgMath.scale(c.zoom, w, h, iw, ih)
                    v.imageMatrix = Matrix().apply { setScale(s, s); postTranslate(w / 2 - c.cx * iw * s, h / 2 - c.cy * ih * s) }
                }
                playBackground(d, play)
            },
            modifier = Modifier.fillMaxSize()
        )
        DisposableEffect(d) { onDispose { playBackground(d, false) } }
    }

    // ───────────── 배경 위치 조정: 한 손가락으로 끌어서 옮기고, 두 손가락으로 확대·축소, 두 번 톡 = 처음대로 ─────────────
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun AdjustOverlay(prefs: SharedPreferences) {
        val start = remember { bgCrop.value }
        val cancel = { bgCrop.value = start; adjusting.value = false }
        BackHandler { cancel() }
        fun fit(c: BgCrop): BgCrop {
            val sz = rootSize.value; val (iw, ih) = bgImg.value
            return if (sz.width == 0 || iw <= 0f) c
            else BgMath.clamp(c, sz.width.toFloat(), sz.height.toFloat(), iw, ih)
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val (iw, ih) = bgImg.value
                        if (iw <= 0f) return@detectTransformGestures
                        val c = BgMath.clamp(bgCrop.value, w, h, iw, ih)
                        val s0 = BgMath.scale(c.zoom, w, h, iw, ih)
                        val z1 = (c.zoom * zoom).coerceIn(BgMath.minZoom(w, h, iw, ih), BgMath.MAX_ZOOM)
                        val s1 = BgMath.scale(z1, w, h, iw, ih)
                        // 손가락 사이 지점의 그림이 손가락을 따라오게: 그 점을 기준으로 확대한 뒤 끈 만큼 이동
                        val px = c.cx * iw + (centroid.x - w / 2) / s0
                        val py = c.cy * ih + (centroid.y - h / 2) / s0
                        val nx = (px - (centroid.x - w / 2) / s1 - pan.x / s1) / iw
                        val ny = (py - (centroid.y - h / 2) / s1 - pan.y / s1) / ih
                        bgCrop.value = BgMath.clamp(BgCrop(nx, ny, z1), w, h, iw, ih)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { bgCrop.value = BgCrop() }) }
                .statusBarsPadding().navigationBarsPadding().padding(16.dp)
        ) {
            Text(
                "끌어서 옮기고, 두 손가락으로 확대·축소\n두 번 톡 치면 처음대로",
                color = Ink, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 10.dp)
            )
            FlowRow(
                Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.85f)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SoftButton("꽉 채우기") { bgCrop.value = fit(bgCrop.value.copy(zoom = 1f)) }
                SoftButton("전체 보기") { bgCrop.value = fit(BgCrop(0.5f, 0.5f, 0f)) }
                SoftButton("취소") { cancel() }
                PillButton("완료") {
                    val c = fit(bgCrop.value)
                    bgCrop.value = c
                    saveCrop(prefs, c)
                    adjusting.value = false
                }
            }
        }
    }

    // 흰 바탕 보조 버튼 (주 버튼은 민트색 PillButton)
    @Composable
    fun SoftButton(text: String, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(14.dp))
                .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp)
        ) { Text(text, color = Ink, fontWeight = FontWeight.SemiBold) }
    }

    @Composable
    fun StatusPill(connected: Boolean) {
        val dot by animateColorAsState(if (connected) MintD else Color(0xFFC3CCD6), label = "dot")
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(cardBg()).border(1.dp, cardLine(), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
            Text(if (connected) "연결됨" else "연결 중", fontSize = 13.sp, color = Ink)
        }
    }

    @Composable
    fun SettingsCard(prefs: SharedPreferences, pick: () -> Unit) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().padding(bottom = 14.dp)
                .heightIn(max = 460.dp)
                .clip(shape).background(cardBg()).border(1.dp, cardLine(), shape)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (status.value != "연결됨") {
                Text(status.value, color = Sub, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
            }
            if (needKey.value) {
                var input by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, label = { Text("PC 화면의 비밀 키") },
                        singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    PillButton("저장") {
                        if (input.isNotBlank()) {
                            prefs.edit().putString("key", input.trim()).apply()
                            needKey.value = false
                            paused = false                    // 새 키로 다시 연결 시작
                            ws?.cancel(); ws = null
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            var ip by remember { mutableStateOf(prefs.getString("manual", "") ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ip, { ip = it }, label = { Text("PC IP (예: 192.168.100.47)") },
                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PillButton("연결") {
                    prefs.edit().putString("manual", ip.trim()).apply()
                    paused = false
                    ws?.cancel(); ws = null                   // 새 IP로 바로 다시 연결
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("효과음", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("버튼을 누를 때 '톡' 소리", color = Sub, fontSize = 12.sp)
                }
                Switch(
                    checked = soundOn.value,
                    onCheckedChange = {
                        soundOn.value = it
                        prefs.edit().putBoolean("sound", it).apply()     // 다시 바꿀 때까지 저장
                        if (it) ticker.play("tick")
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = Color.White)
                )
            }
            // ── 화면 꾸미기 ──
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Spacer(Modifier.height(12.dp))
            Text("화면 꾸미기", color = Sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("배경", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ChipRow(listOf("basic" to "기본", "custom" to "내 사진·GIF"), bgMode.value) {
                bgNote.value = null
                if (it == "custom" && !bgFile().exists()) pick()        // 아직 고른 파일이 없으면 바로 고르기 창
                else { bgMode.value = it; prefs.edit().putString("bg", it).apply() }
            }
            if (bgMode.value == "custom") {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("가림막", color = Sub, fontSize = 13.sp, modifier = Modifier.width(52.dp))
                    ChipRow(listOf(0 to "없음", 1 to "살짝", 2 to "진하게"), veil.intValue) {
                        veil.intValue = it
                        prefs.edit().putInt("veil", it).apply()
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    PillButton("위치 조정") { bgNote.value = null; adjusting.value = true }
                    Spacer(Modifier.width(8.dp))
                    SoftButton("다른 파일 고르기") { bgNote.value = null; pick() }
                }
            }
            bgNote.value?.let { Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
            Text("배경 움직임", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ChipRow(listOf(0 to "멈춤", 1 to "천천히", 2 to "부드럽게"), motion.intValue) {
                motion.intValue = it
                prefs.edit().putInt("motion", it).apply()
            }
            Text(
                if (bgMode.value == "custom") {
                    if (motion.intValue == 0) "GIF가 멈춰 있어. 배터리를 가장 적게 써"
                    else "GIF를 원래 속도로 반복 재생해. 앱이 뒤로 가면 멈춰"
                } else when (motion.intValue) {
                    0 -> "배경이 멈춰 있어. 배터리를 가장 적게 써"
                    1 -> "초당 10번만 살짝 움직여. 배터리 부담이 작아"
                    else -> "화면 갱신마다 움직여. 제일 부드럽지만 배터리를 더 써"
                }, color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("유리 효과", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("버튼과 카드가 반투명해져서 배경이 비쳐", color = Sub, fontSize = 12.sp)
                }
                Switch(
                    checked = glass.value,
                    onCheckedChange = { glass.value = it; prefs.edit().putBoolean("glass", it).apply() },
                    colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = Color.White)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("누를 때 효과", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ChipRow(FX_LIST, fx.value) {
                fx.value = it
                prefs.edit().putString("fx", it).apply()
            }
            Spacer(Modifier.height(10.dp))
            if (!needKey.value) {
                Text("비밀 키 다시 입력", color = MintD, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp).clickable { needKey.value = true })
            }
        }
    }

    // 여러 개 중 하나 고르는 동글한 칩 줄 (넘치면 다음 줄로)
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun <T> ChipRow(items: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, name) in items) {
                val on = key == selected
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier.clip(shape)
                        .background(if (on) MintL else Color.White.copy(alpha = 0.7f))
                        .border(if (on) 1.5.dp else 1.dp, if (on) MintD else Line, shape)
                        .clickable { onPick(key) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(name, color = if (on) MintD else Ink, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }

    @Composable
    fun PillButton(text: String, onClick: () -> Unit) {
        val src = remember { MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "pill")
        Box(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(14.dp)).background(Mint)
                .clickable(interactionSource = src, indication = ripple(color = Color.White)) { onClick() }
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold) }
    }

    // ───────────── 버튼 한 칸: 눌리고, 효과가 터지고, 체크/흔들림으로 결과 표시 ─────────────
    @Composable
    fun DeckTile(b: DeckButton, result: Pair<Boolean, Long>?, onPress: () -> Unit) {
        val view = LocalView.current
        val src = remember { MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        val effect = fx.value
        val isGlass = glass.value
        // 누르면 쑥 들어가고, 떼면 살짝 튕기며 돌아옴 (통통 효과는 더 깊게, 더 많이 튕김)
        val bouncy = effect == "bounce"
        val scale by animateFloatAsState(
            if (pressed) (if (bouncy) 0.86f else 0.93f) else 1f,
            animationSpec = if (pressed) tween(80)
                else spring(dampingRatio = if (bouncy) 0.28f else 0.45f, stiffness = if (bouncy) 380f else 500f),
            label = "scale"
        )
        // 반투명(유리) 버튼은 그림자가 버튼 안으로 비쳐 얼룩이 생기니까 그림자 없이 테두리·빛으로 입체감을 줌
        val seeThrough = isGlass && b.mode != "image"
        val elev by animateDpAsState(if (seeThrough) 0.dp else if (pressed) 1.dp else 8.dp, label = "elev")
        val badge = remember { Animatable(0f) }
        val shake = remember { Animatable(0f) }
        val failTint = remember { Animatable(0f) }

        // 누를 때 효과: 누른 위치에서 시작해서 0.65초 동안 재생되고 끝남 (계속 돌지 않음)
        val fxP = remember { Animatable(1f) }
        var fxPos by remember { mutableStateOf(Offset.Zero) }
        var fxSeed by remember { mutableIntStateOf(0) }
        LaunchedEffect(src) {
            src.interactions.collect { i ->
                if (i is PressInteraction.Press) {
                    fxPos = i.pressPosition
                    fxSeed++
                    launch { fxP.snapTo(0f); fxP.animateTo(1f, tween(650, easing = LinearOutSlowInEasing)) }
                }
            }
        }

        LaunchedEffect(result?.second) {
            val r = result ?: return@LaunchedEffect
            if (r.first) {
                badge.snapTo(0f)
                badge.animateTo(1f, tween(1300, easing = LinearEasing))
            } else {
                launch { failTint.snapTo(1f); failTint.animateTo(0f, tween(650)) }
                shake.snapTo(0f)
                shake.animateTo(1f, tween(500, easing = LinearEasing))
                shake.snapTo(0f)
            }
        }
        val sv = shake.value
        val shakeX = if (sv > 0f && sv < 1f) (sin(sv * PI * 6) * 8 * (1 - sv)).toFloat() else 0f
        val shape = RoundedCornerShape(24.dp)
        val light = b.text == Ink                                    // 밝은 버튼인지
        val shadowCol = b.shade.dim(if (light) 0.45f else 0.6f)       // 버튼 자기 색의 그림자
        // 바탕: 위가 살짝 밝은 세로 그라데이션. 유리 효과면 반투명
        val a = if (isGlass && b.mode != "image") (if (light) 0.6f else 0.82f) else 1f
        val face = Brush.verticalGradient(listOf(
            lerpColor(b.color, Color.White, 0.08f).copy(alpha = a), b.color.dim(0.96f).copy(alpha = a)))
        val edge = if (isGlass) Color.White.copy(alpha = 0.85f)
            else if (light && b.mode != "image") Line else Color.Transparent

        Box(
            Modifier.aspectRatio(1f)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = shakeX }
                .shadow(elev, shape, ambientColor = shadowCol, spotColor = shadowCol)
                .clip(shape)
                .background(face)
                .border(1.dp, edge, shape)
                .clickable(interactionSource = src, indication = if (effect == "ripple") ripple(color = Mint) else null) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPress()
                }
                .drawWithContent {
                    drawContent()
                    // 위쪽 가장자리 빛 반사 (살짝 볼록한 느낌)
                    drawRect(Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (b.mode == "image") 0.18f else 0.35f),
                        0.22f to Color.Transparent))
                    val p = fxP.value
                    if (p < 1f) drawPressFx(effect, p, fxPos, fxSeed, b)
                }
        ) {
            when (b.mode) {
                "image" -> {
                    // 이미지가 버튼 전체를 덮고, 아래쪽만 살짝 어둡게 해서 이름이 잘 보이게
                    Image(b.icon!!, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xAA0F1626))))
                    Text(b.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
                "glyph" -> Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 아이콘 뒤에 은은한 원
                    Box(
                        Modifier.fillMaxWidth(0.52f).aspectRatio(1f).clip(CircleShape)
                            .background(b.text.copy(alpha = if (light) 0.08f else 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(b.icon!!, null, modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(b.label, color = b.text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> Text(b.label, color = b.text, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(10.dp))
            }

            // 실패하면 붉은빛이 돌았다가 사라짐
            if (failTint.value > 0f) {
                Box(Modifier.fillMaxSize().background(Danger.copy(alpha = 0.45f * failTint.value)))
            }
            // 성공하면 체크 배지가 톡 떴다가 사라짐
            val v = badge.value
            if (v > 0f && v < 1f) {
                val alpha = min(1f, v * 7) * min(1f, (1 - v) * 4)
                val pop = 0.6f + 0.4f * min(1f, v * 7) + 0.12f * sin(min(1f, v * 7) * PI).toFloat()
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp)
                        .graphicsLayer { this.alpha = alpha; scaleX = pop; scaleY = pop }
                        .clip(CircleShape).background(MintD),
                    contentAlignment = Alignment.Center
                ) { Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    // 두 색 사이 (t = 0이면 a, 1이면 b)
    private fun lerpColor(a: Color, b: Color, t: Float) =
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha)

    // 누를 때 효과 그리기. p: 0(막 누름) → 1(끝)
    private fun DrawScope.drawPressFx(effect: String, p: Float, at: Offset, seed: Int, b: DeckButton) {
        val fade = 1f - p
        val big = max(size.width, size.height)
        val accent = if (b.text == Ink) MintD else Color.White
        when (effect) {
            "sparkle" -> {
                // 누른 곳에서 작은 빛 알갱이 14개가 사방으로 퍼지며 사라짐
                drawCircle(Color.White.copy(alpha = 0.55f * fade), big * 0.12f * (0.4f + p), at)
                val rnd = Random(seed)
                repeat(14) { k ->
                    val ang = (k / 14f) * 2 * PI.toFloat() + rnd.nextFloat() * 0.5f
                    val dist = big * (0.18f + 0.32f * rnd.nextFloat()) * (1 - (1 - p) * (1 - p))
                    val pos = at + Offset(cos(ang) * dist, sin(ang) * dist)
                    val col = when (k % 3) { 0 -> Color.White; 1 -> Mint; else -> accent }
                    drawCircle(col.copy(alpha = fade), (3.5f + 3f * rnd.nextFloat()) * density * (0.4f + 0.6f * fade), pos)
                }
            }
            "glow" -> {
                // 누른 곳에서 부드러운 빛이 번지고, 테두리 고리가 퍼짐
                val r = big * (0.25f + 1.0f * p)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f * fade), Color.Transparent), at, r), r, at)
                drawCircle(accent.copy(alpha = 0.6f * fade), big * (0.15f + 0.7f * p), at,
                    style = Stroke(width = 3f * density * fade + 1f))
            }
            "bounce" -> drawRect(Color.White.copy(alpha = 0.18f * fade))   // 통통: 크기 변화가 주인공이라 빛은 살짝만
        }
    }

    override fun onDestroy() {
        ws?.close(1000, null)
        scope.cancel()
        ticker.release()
        super.onDestroy()
    }
}

package com.nahyunung.screentranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * 화면 캡처(MediaProjection) + 떠 있는 번역 버튼 + 번역 결과 오버레이.
 * 흐름: 버튼 탭 → 버튼 숨김 → 최신 화면 프레임 → OCR → 번역 → 원래 글자 위치에 덮어쓰기
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.nahyunung.screentranslator.STOP"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        /** 캡처 해상도 상한 (가로 기준). 메모리·속도용. */
        private const val MAX_CAPTURE_WIDTH = 1080

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    // 캡처
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private val imageLock = Any()
    private var latest: Image? = null

    private var screenW = 0
    private var screenH = 0
    private var densityDpi = 0
    private var capW = 0
    private var capH = 0

    // 번역
    private lateinit var lang: SourceLang
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null

    // 화면 위 뷰
    private var button: TextView? = null
    private lateinit var buttonParams: WindowManager.LayoutParams
    private var overlay: TranslateOverlayView? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 화면 캡처 전에 반드시 포그라운드 서비스부터 (안드로이드 10+ 규칙)
        startForegroundCompat()

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 이미 켜져 있었으면 깨끗이 정리하고 다시 시작
        teardown()

        lang = Prefs.source(this)
        recognizer = lang.recognizer()
        translator = lang.translator()

        try {
            setupCapture(code, data)
        } catch (e: Exception) {
            toast("화면 캡처 시작 실패: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        showButton()
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        teardown()
        net.shutdownNow()
        super.onDestroy()
    }

    private fun teardown() {
        hideLangMenu()
        removeOverlay()
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        synchronized(imageLock) {
            latest?.close()
            latest = null
        }
        projection?.let { p ->
            projection = null
            runCatching { p.stop() }
        }
        bgThread?.quitSafely()
        bgThread = null
        recognizer?.close()
        recognizer = null
        translator?.close()
        translator = null
        busy = false
    }

    // ---------------- 알림 ----------------

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "화면 번역", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("화면번역 켜짐")
            .setContentText("떠 있는 '번역' 버튼을 눌러봐")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "끄기", stopPi).build())
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------- 화면 캡처 ----------------

    private fun measureScreen() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        densityDpi = m.densityDpi
        val scale = min(1f, MAX_CAPTURE_WIDTH.toFloat() / screenW)
        capW = (screenW * scale).toInt()
        capH = (screenH * scale).toInt()
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            // 항상 "가장 최근 프레임 1장"만 들고 있음
            val img = try {
                ir.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            }
            if (img != null) {
                synchronized(imageLock) {
                    latest?.close()
                    latest = img
                }
            }
        }, Handler(bgThread!!.looper))
        return r
    }

    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("권한 없음")
        projection = mp
        // 안드로이드 14+: 가상 디스플레이 만들기 전에 콜백 등록 필수
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 상단 '공유 중지' 등으로 캡처가 끊기면 서비스도 종료
                main.post { if (projection === mp) stopSelf() }
            }
        }, main)

        measureScreen()
        bgThread = HandlerThread("capture").also { it.start() }
        reader = newReader()
        virtualDisplay = mp.createVirtualDisplay(
            "ScreenTranslator", capW, capH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    /** 화면 회전 시 캡처 크기 맞추기 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ resizeCapture() }, 300)
    }

    private fun resizeCapture() {
        val vd = virtualDisplay ?: return
        val oldW = screenW
        val oldH = screenH
        measureScreen()
        if (oldW == screenW && oldH == screenH) return
        val old = reader
        reader = newReader()
        vd.resize(capW, capH, densityDpi)
        vd.surface = reader!!.surface
        synchronized(imageLock) {
            latest?.close()
            latest = null
        }
        old?.close()
        removeOverlay()
        // 버튼이 화면 밖으로 나가지 않게
        button?.let {
            buttonParams.x = buttonParams.x.coerceIn(0, screenW - it.width.coerceAtLeast(1))
            buttonParams.y = buttonParams.y.coerceIn(0, screenH - it.height.coerceAtLeast(1))
            runCatching { wm.updateViewLayout(it, buttonParams) }
        }
    }

    private fun grabBitmap(): Bitmap? {
        synchronized(imageLock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            // 줄 끝에 패딩이 붙어 있을 수 있어서 넉넉히 만든 뒤 잘라냄
            val rowPixels = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)
            if (rowPixels == img.width) return full
            val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            full.recycle()
            return cropped
        }
    }

    // ---------------- 떠 있는 버튼 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buttonLabel(): String {
        val e = Prefs.engine(this)
        return if (e == Engine.GEMINI) "번역\nAI·${lang.short}" else "번역\n${lang.short}"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        val size = dp(56)
        val tv = TextView(this).apply {
            text = buttonLabel()
            textSize = 12f
            setLineSpacing(0f, 0.9f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E62F6BFF"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        buttonParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - dp(8)
            y = screenH / 3
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showLangMenu()
        }
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = buttonParams.x; startY = buttonParams.y
                    moved = false
                    longPressed = false
                    main.postDelayed(longPress, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && !longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        main.removeCallbacks(longPress)
                        hideLangMenu()
                    }
                    if (moved) {
                        buttonParams.x = startX + dx.toInt()
                        buttonParams.y = startY + dy.toInt()
                        wm.updateViewLayout(v, buttonParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved && !longPressed) onButtonTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, buttonParams)
        button = tv
    }

    private fun setButtonState(label: String, visible: Boolean = true) {
        button?.text = label
        button?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    // ---------------- 언어 선택 메뉴 (버튼 길게 누르기) ----------------

    private var langMenu: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showLangMenu() {
        hideLangMenu()
        val btnSize = dp(56)
        val menuW = dp(104)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(240, 30, 30, 34))
            }
        }
        SourceLang.values().forEach { l ->
            val selected = l == lang
            menu.addView(TextView(this).apply {
                text = if (selected) "✓ ${l.label}" else l.label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(10))
                if (selected) background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#2F6BFF"))
                }
                setOnClickListener {
                    hideLangMenu()
                    switchLang(l)
                }
            })
        }
        // 구분선 + 엔진 전환
        menu.addView(View(this).apply {
            setBackgroundColor(Color.argb(80, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
        })
        val engine = Prefs.engine(this)
        menu.addView(TextView(this).apply {
            text = if (engine == Engine.GEMINI) "엔진: AI ⇄" else "엔진: 오프 ⇄"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                hideLangMenu()
                switchEngine(if (engine == Engine.GEMINI) Engine.MLKIT else Engine.GEMINI)
            }
        })
        // 버튼이 화면 오른쪽에 있으면 메뉴는 왼쪽, 아니면 오른쪽에
        val onRight = buttonParams.x + btnSize / 2 > screenW / 2
        val p = WindowManager.LayoutParams(
            menuW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onRight) buttonParams.x - menuW - dp(8) else buttonParams.x + btnSize + dp(8)
            y = buttonParams.y
        }
        // 메뉴 바깥을 누르면 닫기
        menu.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideLangMenu()
                true
            } else false
        }
        wm.addView(menu, p)
        langMenu = menu
    }

    private fun hideLangMenu() {
        langMenu?.let { runCatching { wm.removeView(it) } }
        langMenu = null
    }

    private fun switchLang(l: SourceLang) {
        if (l == lang) return
        if (busy) {
            toast("번역 중엔 바꿀 수 없어")
            return
        }
        removeOverlay()
        recognizer?.close()
        translator?.close()
        lang = l
        Prefs.setSource(this, l)
        recognizer = l.recognizer()
        val tr = l.translator()
        translator = tr
        setButtonState(buttonLabel())
        if (Prefs.engine(this) == Engine.GEMINI) {
            toast("${l.label} → 한국어 (AI)")
            return
        }
        // 오프라인 모드: 번역 모델이 없으면 미리 받아두기
        busy = true
        setButtonState("받는중")
        tr.downloadModelIfNeeded()
            .addOnSuccessListener { toast("${l.label} → 한국어 준비 완료") }
            .addOnFailureListener { e -> toast("${l.label} 모델 다운로드 실패 (인터넷 확인): ${e.message}") }
            .addOnCompleteListener {
                busy = false
                setButtonState(buttonLabel())
            }
    }

    private fun switchEngine(e: Engine) {
        if (busy) {
            toast("번역 중엔 바꿀 수 없어")
            return
        }
        Prefs.setEngine(this, e)
        removeOverlay()
        setButtonState(buttonLabel())
        if (e == Engine.GEMINI) {
            if (Prefs.apiKey(this).isBlank()) toast("앱 설정에 제미나이 API 키를 넣어줘")
            else toast("AI 번역 모드")
            return
        }
        val tr = translator ?: return
        busy = true
        setButtonState("받는중")
        tr.downloadModelIfNeeded()
            .addOnSuccessListener { toast("오프라인 모드 (${lang.label} → 한국어)") }
            .addOnFailureListener { ex -> toast("모델 다운로드 실패 (인터넷 확인): ${ex.message}") }
            .addOnCompleteListener {
                busy = false
                setButtonState(buttonLabel())
            }
    }

    // ---------------- 번역 ----------------

    private fun onButtonTap() {
        if (busy) return
        if (overlay != null) { // 번역 화면 떠 있으면 닫기만
            removeOverlay()
            return
        }
        // 앱 화면에서 언어를 바꿨으면 반영
        val pref = Prefs.source(this)
        if (pref != lang) {
            recognizer?.close(); translator?.close()
            lang = pref
            recognizer = pref.recognizer()
            translator = pref.translator()
        }
        if (Prefs.engine(this) == Engine.GEMINI && Prefs.apiKey(this).isBlank()) {
            toast("앱 설정에 제미나이 API 키를 넣어줘")
            return
        }
        busy = true
        // 버튼이 캡처에 찍히지 않게 잠깐 숨기고, 새 프레임이 올 때까지 대기
        setButtonState(buttonLabel(), visible = false)
        main.postDelayed({ captureAndTranslate() }, 300)
    }

    private fun finishWork(error: String? = null) {
        busy = false
        setButtonState(buttonLabel())
        if (error != null) toast(error)
    }

    private fun captureAndTranslate() {
        val bmp = grabBitmap()
        setButtonState("…")
        if (bmp == null) {
            finishWork("화면을 못 가져왔어. 다시 눌러봐")
            return
        }
        if (Prefs.engine(this) == Engine.GEMINI) {
            translateWithGemini(bmp)
            return
        }
        val rec = recognizer ?: return finishWork()
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result -> translateBlocks(result) }
            .addOnFailureListener { e -> finishWork("글자 인식 실패: ${e.message}") }
            .addOnCompleteListener { bmp.recycle() }
    }

    /** 지금 번역 중인 요청 번호 (사용자가 중간에 닫으면 남은 결과 무시) */
    private var aiSession = 0
    private var aiClosedByUser = false

    private fun translateWithGemini(bmp: Bitmap) {
        val key = Prefs.apiKey(this)
        val model = Prefs.model(this)
        val l = lang
        val w = screenW
        val h = screenH
        val session = ++aiSession
        aiClosedByUser = false
        val started = System.currentTimeMillis()
        setButtonState("AI…")
        net.execute {
            val result = runCatching {
                GeminiClient.translate(
                    bmp, l, key, model, w, h,
                    onStatus = { msg -> main.post { if (session == aiSession) setButtonState(msg) } },
                    onItem = { item ->
                        main.post {
                            if (session != aiSession || aiClosedByUser) return@post
                            // 첫 박스면 오버레이 띄우고, 이후엔 하나씩 추가
                            val ov = overlay
                            if (ov == null) showOverlay(listOf(item)) else ov.addItem(item)
                        }
                    }
                )
            }
            bmp.recycle()
            main.post {
                if (session != aiSession) return@post
                result.onSuccess { count ->
                    if (count == 0) finishWork("번역할 외국어를 못 찾았어")
                    else {
                        val sec = (System.currentTimeMillis() - started) / 100 / 10.0
                        finishWork()
                        if (Prefs.showTiming(this)) toast("${count}개 · ${sec}초")
                    }
                }.onFailure { e ->
                    finishWork(
                        if (e is GeminiClient.GeminiException) e.message
                        else "AI 번역 실패 (인터넷 확인): ${e.message}"
                    )
                }
            }
        }
    }

    private fun blockText(b: Text.TextBlock): String =
        b.lines.joinToString(if (lang.joinWithSpace) " " else "") { it.text }

    private fun translateBlocks(result: Text) {
        val tr = translator ?: return finishWork()
        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.isNotBlank() }
        if (blocks.isEmpty()) {
            finishWork("${lang.label} 글자를 못 찾았어")
            return
        }
        // 모델이 없으면 여기서 받음(인터넷 필요), 있으면 바로 통과
        tr.downloadModelIfNeeded()
            .onSuccessTask { Tasks.whenAllSuccess<String>(blocks.map { tr.translate(blockText(it)) }) }
            .addOnSuccessListener { translations ->
                val s = screenW.toFloat() / capW
                val items = blocks.zip(translations).map { (b, t) ->
                    val r = b.boundingBox!!
                    OverlayItem(
                        rect = Rect(
                            (r.left * s).toInt(), (r.top * s).toInt(),
                            (r.right * s).toInt(), (r.bottom * s).toInt()
                        ),
                        text = t,
                        lineCount = b.lines.size.coerceAtLeast(1)
                    )
                }
                showOverlay(items)
                finishWork()
            }
            .addOnFailureListener { e ->
                finishWork("번역 실패 (앱에서 ② 모델 받기 먼저 해줘): ${e.message}")
            }
    }

    private fun showOverlay(items: List<OverlayItem>) {
        removeOverlay()
        val view = TranslateOverlayView(this, items) {
            aiClosedByUser = true // 스트리밍 중에 닫았으면 남은 박스는 안 띄움
            removeOverlay()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(view, p)
        overlay = view
        // 버튼을 번역 화면보다 위로 다시 올리기
        button?.let {
            runCatching {
                wm.removeView(it)
                wm.addView(it, buttonParams)
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

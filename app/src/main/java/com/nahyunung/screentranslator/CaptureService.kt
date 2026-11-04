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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private val net = Executors.newCachedThreadPool()

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
        scope.cancel()
        NanoClient.close()
        super.onDestroy()
    }

    private fun teardown() {
        job++
        cancelToken?.cancel()
        nanoJob?.cancel()
        NanoGate.close()
        main.removeCallbacks(watchdog)
        hideLangMenu()
        removeRegionSelect()
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
        val top = if (Prefs.regionMode(this)) "번역▢" else "번역"
        val bottom = if (e == Engine.MLKIT) lang.short else "${e.short}·${lang.short}"
        return "$top\n$bottom"
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

    // ---------------- 메뉴 (버튼 길게 누르기) ----------------

    private var langMenu: View? = null

    private fun menuItem(label: String, selected: Boolean = false, small: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = if (small) 13f else 15f
            gravity = Gravity.CENTER
            setTextColor(if (small) Color.argb(230, 255, 255, 255) else Color.WHITE)
            setPadding(0, dp(if (small) 8 else 10), 0, dp(if (small) 8 else 10))
            if (selected) background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#2F6BFF"))
            }
            setOnClickListener {
                hideLangMenu()
                onClick()
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun showLangMenu() {
        hideLangMenu()
        val btnSize = dp(56)
        val menuW = dp(112)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(240, 30, 30, 34))
            }
        }
        // 언어
        SourceLang.values().forEach { l ->
            val selected = l == lang
            menu.addView(menuItem(if (selected) "✓ ${l.label}" else l.label, selected) { switchLang(l) })
        }
        // 구분선
        menu.addView(View(this).apply {
            setBackgroundColor(Color.argb(80, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
        })
        // 엔진 (누를 때마다 정밀 → 빠름 → 오프라인 순환)
        val engine = Prefs.engine(this)
        menu.addView(menuItem("엔진: ${engine.short} ⇄", small = true) { switchEngine(engine.next()) })
        // 영역 선택 ON/OFF
        val region = Prefs.regionMode(this)
        menu.addView(menuItem(if (region) "영역 선택: ON" else "영역 선택: OFF", small = true) {
            Prefs.setRegionMode(this, !region)
            removeRegionSelect()
            setButtonState(buttonLabel())
            toast(if (!region) "영역 선택 ON — 버튼 누르고 영역 드래그 → 버튼 다시 누르기" else "영역 선택 OFF — 화면 전체 번역")
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
        if (Prefs.engine(this).usesGemini) {
            toast("${l.label} → 한국어")
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
        if (e.usesGemini) {
            if (Prefs.apiKey(this).isBlank()) toast("앱 설정에 제미나이 API 키를 넣어줘")
            else toast(if (e == Engine.GEMINI) "AI 정밀 (세로글씨 OK)" else "AI 빠름 (가로글씨용)")
            return
        }
        if (e == Engine.NANO) {
            toast("나노(실험) — 처음이면 앱에서 '나노 모델 확인' 먼저")
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

    // ---------------- 영역 선택 ----------------

    private var regionView: RegionSelectView? = null

    private fun showRegionSelect() {
        removeRegionSelect()
        val v = RegionSelectView(this, Prefs.lastRegion(this)) {
            removeRegionSelect()
            setButtonState(buttonLabel())
        }
        if (!addFullScreenView(v)) return
        regionView = v
        raiseButton()
        setButtonState("영역\n▶보내기")
        // 영역 고르는 동안 서버 연결 미리 열어두기
        warmUpIfAi()
    }

    private fun removeRegionSelect() {
        regionView?.let { runCatching { wm.removeView(it) } }
        regionView = null
    }

    // ---------------- 번역 ----------------

    /** 지금 작업 번호. 중지하면 올라가서, 늦게 도착한 결과는 무시됨 */
    private var job = 0
    private var cancelToken: GeminiClient.CancelToken? = null
    /** 이 시간 넘게 걸리면 자동으로 중지 (먹통 방지) */
    private val watchdog = Runnable { cancelWork("너무 오래 걸려서 중지했어. 다시 눌러봐") }

    private fun busyLabel(status: String) = setButtonState("$status\n■중지")

    /** 번역 도중 중지: 네트워크 끊고, 늦게 오는 결과 무시, 버튼 바로 복구 */
    private fun cancelWork(msg: String) {
        job++
        cancelToken?.cancel()
        nanoJob?.cancel()
        NanoGate.close()
        finishWork(msg)
    }

    private fun warmUpIfAi() {
        if (!Prefs.engine(this).usesGemini) return
        val key = Prefs.apiKey(this).takeIf { it.isNotBlank() } ?: return
        net.execute { GeminiClient.warmUp(key) }
    }

    private fun onButtonTap() {
        if (busy) { // 번역 중에 누르면 중지
            cancelWork("중지했어")
            return
        }
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
        if (Prefs.engine(this).usesGemini && Prefs.apiKey(this).isBlank()) {
            toast("앱 설정에 제미나이 API 키를 넣어줘")
            return
        }

        // 영역 선택 모드: 첫 탭 = 영역 고르기 화면, 두 번째 탭 = 그 영역 보내기
        var region: Rect? = null
        if (Prefs.regionMode(this)) {
            val rv = regionView
            if (rv == null) {
                showRegionSelect()
                return
            }
            region = rv.selectedRect()
            if (region == null) {
                toast("번역할 부분을 먼저 드래그해줘")
                return
            }
            Prefs.setLastRegion(this, region)
            removeRegionSelect()
        } else {
            warmUpIfAi()
        }

        busy = true
        val id = ++job
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 60_000)
        // 버튼(과 영역 선택 화면)이 캡처에 찍히지 않게 잠깐 숨기고, 새 프레임이 올 때까지 대기
        setButtonState(buttonLabel(), visible = false)
        main.postDelayed({ captureAndTranslate(id, region) }, 150)
    }

    private fun finishWork(error: String? = null) {
        main.removeCallbacks(watchdog)
        cancelToken = null
        busy = false
        setButtonState(buttonLabel())
        if (error != null) toast(error)
    }

    /**
     * @param region 번역할 화면 영역 (null이면 전체 화면)
     */
    private fun captureAndTranslate(id: Int, region: Rect?) {
        if (id != job) return // 그새 중지됨
        val full = grabBitmap()
        busyLabel("…")
        if (full == null) {
            finishWork("화면을 못 가져왔어. 다시 눌러봐")
            return
        }
        // 이미지가 화면에서 차지하는 영역 (결과 박스를 화면 좌표로 바꿀 때 기준)
        val screenRect = Rect(0, 0, screenW, screenH)
        val target = region?.let { Rect(it).apply { if (!intersect(screenRect)) setEmpty() } } ?: screenRect
        if (target.isEmpty) {
            full.recycle()
            finishWork("영역이 화면 밖이야. 다시 골라줘")
            return
        }
        val bmp = if (region == null) full else {
            // 화면 좌표 → 캡처 이미지 좌표로 바꿔서 잘라내기
            val s = full.width.toFloat() / screenW
            val l = (target.left * s).toInt().coerceIn(0, full.width - 1)
            val t = (target.top * s).toInt().coerceIn(0, full.height - 1)
            val w = (target.width() * s).toInt().coerceIn(1, full.width - l)
            val h = (target.height() * s).toInt().coerceIn(1, full.height - t)
            Bitmap.createBitmap(full, l, t, w, h).also { if (it !== full) full.recycle() }
        }

        when (Prefs.engine(this)) {
            Engine.GEMINI -> translateWithGemini(bmp, target, id)
            Engine.HYBRID -> recognizeThen(bmp, id) { blocks -> translateHybrid(blocks, bmp, target, id) }
            Engine.MLKIT -> recognizeThen(bmp, id) { blocks -> translateOffline(blocks, bmp, target, id) }
            Engine.NANO -> recognizeThen(bmp, id) { blocks -> translateNano(blocks, bmp, target, id) }
        }
    }

    /** 폰 안에서 글자 찾기(ML Kit OCR) → 결과 블록 넘겨줌 */
    private fun recognizeThen(bmp: Bitmap, id: Int, next: (List<Text.TextBlock>) -> Unit) {
        val rec = recognizer ?: run { bmp.recycle(); return finishWork() }
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                if (id != job) return@addOnSuccessListener
                val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.isNotBlank() }
                if (blocks.isEmpty()) finishWork("${lang.label} 글자를 못 찾았어")
                else next(blocks)
            }
            .addOnFailureListener { e -> if (id == job) finishWork("글자 인식 실패: ${e.message}") }
            .addOnCompleteListener { bmp.recycle() }
    }

    /** OCR 블록 좌표(잘라낸 이미지 기준) → 화면 좌표 */
    private fun blockRect(b: Text.TextBlock, bmp: Bitmap, target: Rect): Rect {
        val r = b.boundingBox!!
        val sx = target.width().toFloat() / bmp.width
        val sy = target.height().toFloat() / bmp.height
        return Rect(
            target.left + (r.left * sx).toInt(), target.top + (r.top * sy).toInt(),
            target.left + (r.right * sx).toInt(), target.top + (r.bottom * sy).toInt()
        )
    }

    private fun blockText(b: Text.TextBlock): String =
        b.lines.joinToString(if (lang.joinWithSpace) " " else "") { it.text }

    /** 스트리밍 중에 사용자가 번역 화면을 닫았는지 (닫았으면 남은 박스 무시) */
    private var aiClosedByUser = false

    /** 결과 박스 하나 화면에 추가 (첫 박스면 번역 화면 띄움) */
    private fun deliver(item: OverlayItem, session: Int) {
        if (session != job || aiClosedByUser) return
        val ov = overlay
        if (ov == null) showOverlay(listOf(item)) else ov.addItem(item)
    }

    private fun finishAi(session: Int, started: Long, result: Result<Int>, cachedCount: Int = 0, note: String = "") {
        if (session != job) return
        result.onSuccess { count ->
            val total = count + cachedCount
            if (total == 0) finishWork("번역할 외국어를 못 찾았어")
            else {
                val sec = (System.currentTimeMillis() - started) / 100 / 10.0
                finishWork()
                if (Prefs.showTiming(this) || note.contains("→")) {
                    val head = if (note.isNotEmpty()) "$note · " else ""
                    toast(head + if (cachedCount > 0) "${total}개 (저장된 ${cachedCount}개) · ${sec}초" else "${total}개 · ${sec}초")
                }
            }
        }.onFailure { e ->
            finishWork(
                if (e is GeminiClient.GeminiException) e.message
                else "AI 번역 실패 (인터넷 확인): ${e.message}"
            )
        }
    }

    /** [AI 정밀] 이미지째 보내기 */
    private fun translateWithGemini(bmp: Bitmap, target: Rect, session: Int) {
        val key = Prefs.apiKey(this)
        val model = Prefs.model(this)
        val l = lang
        val relaxed = Prefs.relaxedSafety(this)
        aiClosedByUser = false
        val started = System.currentTimeMillis()
        val token = GeminiClient.CancelToken()
        cancelToken = token
        busyLabel("AI…")
        net.execute {
            val result = runCatching {
                GeminiClient.translateImage(
                    bmp, l, key, model, target,
                    relaxedSafety = relaxed,
                    cancel = token,
                    onStatus = { msg -> main.post { if (session == job) busyLabel(msg) } },
                    onItem = { item -> main.post { deliver(item, session) } }
                )
            }
            bmp.recycle()
            main.post { finishAi(session, started, result) }
        }
    }

    /** 번역할 글자 하나: 화면 위치, 원문, 줄 수 */
    private data class Entry(val rect: Rect, val text: String, val lines: Int)

    private fun entriesOf(blocks: List<Text.TextBlock>, bmp: Bitmap, target: Rect) = blocks.map { b ->
        Entry(blockRect(b, bmp, target), blockText(b), b.lines.size.coerceAtLeast(1))
    }

    /** 전에 번역한 문장은 바로 표시하고, 남은 것만 돌려줌 */
    private fun deliverCached(entries: List<Entry>, ns: String, session: Int): Pair<List<Entry>, Int> {
        val todo = mutableListOf<Entry>()
        var cached = 0
        for (e in entries) {
            val hit = TranslationCache.get(lang, e.text, ns)
            if (hit != null) {
                deliver(OverlayItem(e.rect, hit, e.lines), session)
                cached++
            } else todo += e
        }
        return todo to cached
    }

    /** [AI 빠름] 폰에서 찾은 글자만 보내기 + 번역 저장해서 재사용 */
    private fun translateHybrid(blocks: List<Text.TextBlock>, bmp: Bitmap, target: Rect, session: Int) {
        val started = System.currentTimeMillis()
        aiClosedByUser = false
        val (todo, cached) = deliverCached(entriesOf(blocks, bmp, target), "", session)
        if (todo.isEmpty()) {
            finishAi(session, started, Result.success(0), cached)
            return
        }
        sendTextsToGemini(todo, session, started, cached)
    }

    /** 글자들을 제미나이로 번역 (AI 빠름 / 나노 실패 시 대체) */
    private fun sendTextsToGemini(todo: List<Entry>, session: Int, started: Long, cached: Int, note: String = "") {
        val l = lang
        val key = Prefs.apiKey(this)
        val model = Prefs.model(this)
        val relaxed = Prefs.relaxedSafety(this)
        val token = GeminiClient.CancelToken()
        cancelToken = token
        busyLabel("AI…")
        val texts = todo.map { it.text }
        net.execute {
            val result = runCatching {
                GeminiClient.translateTexts(
                    texts, l, key, model,
                    relaxedSafety = relaxed,
                    cancel = token,
                    onStatus = { msg -> main.post { if (session == job) busyLabel(msg) } },
                    onTranslated = { i, ko ->
                        main.post {
                            TranslationCache.put(l, texts[i], ko)
                            val e = todo[i]
                            deliver(OverlayItem(e.rect, ko, e.lines), session)
                        }
                    }
                )
            }
            main.post { finishAi(session, started, result, cached, note) }
        }
    }

    // ---------------- [실험] 나노 ----------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var nanoJob: Job? = null

    /** [나노] 폰 안의 Gemini Nano로 번역. 막히면 AI 빠름으로 자동 전환 */
    private fun translateNano(blocks: List<Text.TextBlock>, bmp: Bitmap, target: Rect, session: Int) {
        val started = System.currentTimeMillis()
        aiClosedByUser = false
        val l = lang
        val (todo, cached) = deliverCached(entriesOf(blocks, bmp, target), "nano:", session)
        if (todo.isEmpty()) {
            finishAi(session, started, Result.success(0), cached)
            return
        }
        busyLabel("나노…")
        nanoJob = scope.launch {
            val texts = todo.map { it.text }
            var delivered = 0
            val result = runCatching {
                // 나노는 우리 앱이 맨 앞일 때만 동작 → 투명 화면을 잠깐 띄움
                val front = NanoGate.open(this@CaptureService)
                if (!front) throw IllegalStateException("투명 화면을 못 띄웠어")
                try {
                    NanoClient.translateLines(texts, l) { i, ko ->
                        TranslationCache.put(l, texts[i], ko, "nano:")
                        val e = todo[i]
                        deliver(OverlayItem(e.rect, ko, e.lines), session)
                        delivered++
                    }
                } finally {
                    NanoGate.close()
                }
            }
            if (session != job) return@launch
            val err = result.exceptionOrNull()
            if (err is CancellationException) return@launch
            // 하나도 못 받았고 제미나이 키가 있으면 AI 빠름으로 대신 번역
            if (err != null && delivered == 0 && Prefs.apiKey(this@CaptureService).isNotBlank()) {
                val why = when {
                    NanoClient.isBackgroundBlocked(err) -> "나노 막힘(앞 화면 아님)"
                    else -> "나노 실패"
                }
                sendTextsToGemini(todo, session, started, cached, note = "$why → AI 빠름으로 번역")
                return@launch
            }
            finishAi(
                session, started,
                result.map { delivered }.recoverCatching {
                    if (delivered > 0) delivered
                    else throw Exception(
                        if (NanoClient.isBackgroundBlocked(it)) "나노가 막혔어 (앱이 앞 화면이 아니래)"
                        else "나노 번역 실패: ${it.message}"
                    )
                },
                cached, note = "나노"
            )
        }
    }

    /** [오프라인] ML Kit 번역 */
    private fun translateOffline(blocks: List<Text.TextBlock>, bmp: Bitmap, target: Rect, id: Int) {
        val tr = translator ?: return finishWork()
        val rects = blocks.map { blockRect(it, bmp, target) }
        // 모델이 없으면 여기서 받음(인터넷 필요), 있으면 바로 통과
        tr.downloadModelIfNeeded()
            .onSuccessTask { Tasks.whenAllSuccess<String>(blocks.map { tr.translate(blockText(it)) }) }
            .addOnSuccessListener { translations ->
                if (id != job) return@addOnSuccessListener
                val items = blocks.indices.map { i ->
                    OverlayItem(rects[i], translations[i], blocks[i].lines.size.coerceAtLeast(1))
                }
                showOverlay(items)
                finishWork()
            }
            .addOnFailureListener { e ->
                if (id == job) finishWork("번역 실패 (앱에서 ② 모델 받기 먼저 해줘): ${e.message}")
            }
    }

    /** 화면 전체를 덮는 창 추가. 실패하면 false */
    private fun addFullScreenView(v: View): Boolean {
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
        return try {
            wm.addView(v, p)
            true
        } catch (e: Exception) {
            toast("화면을 못 띄웠어: ${e.message}")
            false
        }
    }

    /** 떠 있는 버튼을 다른 창들보다 위로 다시 올리기 */
    private fun raiseButton() {
        button?.let {
            runCatching {
                wm.removeView(it)
                wm.addView(it, buttonParams)
            }
        }
    }

    private fun showOverlay(items: List<OverlayItem>) {
        removeOverlay()
        val view = TranslateOverlayView(this, items) {
            aiClosedByUser = true // 스트리밍 중에 닫았으면 남은 박스는 안 띄움
            removeOverlay()
        }
        if (!addFullScreenView(view)) return
        overlay = view
        raiseButton()
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

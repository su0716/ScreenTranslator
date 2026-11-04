package com.nahyunung.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 100
        private const val REQ_NOTIF = 101
    }

    private lateinit var status: TextView
    private lateinit var modelBtn: Button
    private lateinit var langGroup: RadioGroup
    private lateinit var engineGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // 떠 있는 버튼에서 언어를 바꿨을 수도 있으니 다시 반영
        if (::langGroup.isInitialized) langGroup.check(1000 + Prefs.source(this).ordinal)
        if (::engineGroup.isInitialized) engineGroup.check(2000 + Prefs.engine(this).ordinal)
        refreshStatus()
    }

    // ---------- UI (코드로 직접 구성) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "화면번역"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "다른 앱 위에 떠 있는 '번역' 버튼을 누르면 지금 화면의 글자를 한국어로 덮어씌워 줘. 번역 박스는 끌어서 옮길 수 있고, 빈 곳을 탭하면 닫혀. 버튼을 꾹 누르면 언어·엔진을 바꿀 수 있고, 번역 중에 누르면 중지돼."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(24))
        })

        // 원문 언어 선택
        root.addView(label("원문 언어"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val current = Prefs.source(this)
        SourceLang.values().forEachIndexed { i, lang ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = lang.label
                textSize = 16f
                isChecked = lang == current
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setSource(this, SourceLang.values()[checkedId - 1000])
            refreshStatus()
        }
        root.addView(group)
        langGroup = group

        // 번역 엔진
        root.addView(label("번역 엔진").apply { setPadding(0, dp(20), 0, dp(8)) })
        val engineGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val curEngine = Prefs.engine(this)
        Engine.values().forEachIndexed { i, e ->
            engineGroup.addView(RadioButton(this).apply {
                id = 2000 + i
                text = if (e == Engine.GEMINI) "AI (제미나이) — 고품질, 세로글씨 OK, 인터넷 필요"
                       else "오프라인 (ML Kit) — 빠름, 품질 낮음"
                textSize = 15f
                isChecked = e == curEngine
            })
        }
        engineGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setEngine(this, Engine.values()[id - 2000])
            refreshStatus()
        }
        root.addView(engineGroup)
        this.engineGroup = engineGroup

        // 제미나이 설정
        root.addView(label("제미나이 API 키 (aistudio.google.com에서 발급)").apply { setPadding(0, dp(16), 0, dp(4)) })
        val keyInput = EditText(this).apply {
            hint = "여기에 키 붙여넣기"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
        }
        root.addView(keyInput)
        root.addView(label("모델 (보통 그대로 두기)").apply { setPadding(0, dp(8), 0, dp(4)) })
        val modelInput = EditText(this).apply {
            setSingleLine()
            setText(Prefs.model(this@MainActivity))
        }
        root.addView(modelInput)
        root.addView(CheckBox(this).apply {
            text = "필터 완화 (게임 채팅 욕설 등도 번역)"
            textSize = 15f
            isChecked = Prefs.relaxedSafety(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setRelaxedSafety(this@MainActivity, v) }
        })
        root.addView(bigButton("키·모델 저장") {
            Prefs.setApiKey(this, keyInput.text.toString())
            Prefs.setModel(this, modelInput.text.toString().ifBlank { Prefs.DEFAULT_MODEL })
            modelInput.setText(Prefs.model(this))
            Toast.makeText(this, "저장했어", Toast.LENGTH_SHORT).show()
            refreshStatus()
        })

        root.addView(label("준비").apply { setPadding(0, dp(24), 0, dp(8)) })
        root.addView(bigButton("① 다른 앱 위에 표시 권한 켜기") { openOverlaySettings() })
        modelBtn = bigButton("② 오프라인 번역 모델 받기 (오프라인 엔진용)") { downloadModel() }
        root.addView(modelBtn)

        root.addView(label("실행").apply { setPadding(0, dp(24), 0, dp(8)) })
        root.addView(bigButton("▶ 번역 버튼 띄우기", primary = true) { start() })
        root.addView(bigButton("■ 끄기") {
            stopService(Intent(this, CaptureService::class.java))
            refreshStatus()
        })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(status)

        return ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.GRAY)
        setPadding(0, 0, 0, dp(8))
    }

    private fun bigButton(t: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 16f
        isAllCaps = false
        gravity = Gravity.CENTER
        if (primary) {
            setBackgroundColor(Color.parseColor("#2F6BFF"))
            setTextColor(Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { bottomMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val overlay = if (Settings.canDrawOverlays(this)) "✅ 켜짐" else "❌ 꺼짐"
        val running = if (CaptureService.running) "🟢 실행 중" else "⚪ 꺼짐"
        val engine = Prefs.engine(this)
        val key = if (Prefs.apiKey(this).isBlank()) "❌ 없음" else "✅ 저장됨"
        status.text = "표시 권한: $overlay\n번역 버튼: $running\n원문 언어: ${Prefs.source(this).label} → 한국어\n" +
            "엔진: ${engine.label}" + (if (engine == Engine.GEMINI) "\nAPI 키: $key" else "")
    }

    // ---------- 권한 / 모델 ----------

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun downloadModel() {
        val lang = Prefs.source(this)
        modelBtn.isEnabled = false
        modelBtn.text = "${lang.label} 모델 받는 중…"
        val translator = lang.translator()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelBtn.text = "② ${lang.label}→한국어 모델 준비 완료 ✅"
                translator.close()
            }
            .addOnFailureListener { e ->
                modelBtn.text = "② 번역 모델 받기 (다시 시도)"
                Toast.makeText(this, "다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                translator.close()
            }
            .addOnCompleteListener { modelBtn.isEnabled = true }
    }

    // ---------- 시작 ----------

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 ① 다른 앱 위에 표시 권한을 켜줘", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return // 결과 받은 뒤 다시 start()
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) start() // 거부해도 동작은 함 (알림만 안 보임)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 캡처를 허용해야 번역할 수 있어", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        startForegroundService(svc)
        Toast.makeText(this, "번역 버튼 켜짐! 번역할 앱으로 가서 버튼을 눌러봐", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }
}

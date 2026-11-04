package com.nahyunung.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [실험] 나노는 우리 앱이 맨 앞 화면일 때만 동작해서,
 * 번역하는 동안만 투명한 화면(NanoActivity)을 잠깐 맨 앞에 띄움.
 * (다른 앱 위에 표시 권한이 있으면 뒤에서도 화면을 띄울 수 있음)
 */
object NanoGate {
    private var waiter: CompletableDeferred<Unit>? = null
    private var activity: Activity? = null

    /** 투명 화면을 띄우고, 실제로 맨 앞에 올라올 때까지 기다림. 실패하면 false */
    suspend fun open(ctx: Context): Boolean {
        activity?.let { return true }
        val d = CompletableDeferred<Unit>()
        waiter = d
        val intent = Intent(ctx, NanoActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        return try {
            ctx.startActivity(intent)
            withTimeoutOrNull(2500) { d.await() } != null
        } catch (e: Exception) {
            false
        } finally {
            waiter = null
        }
    }

    fun close() {
        activity?.let {
            it.finish()
            @Suppress("DEPRECATION")
            it.overridePendingTransition(0, 0)
        }
        activity = null
    }

    internal fun onResumed(a: Activity) {
        activity = a
        waiter?.complete(Unit)
    }

    internal fun onGone(a: Activity) {
        if (activity === a) activity = null
    }
}

/** 화면에 아무것도 안 그리는 투명 화면 (나노 호출 동안만 잠깐 존재) */
class NanoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        NanoGate.onResumed(this)
    }

    override fun onPause() {
        super.onPause()
        NanoGate.onGone(this)
        // 사용자가 다른 데를 누르는 등 밀려나면 바로 정리
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        NanoGate.onGone(this)
        super.onDestroy()
    }
}

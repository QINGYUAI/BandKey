package com.bandkey.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 小组件与息屏 NFC 共用的轻量反馈界面。
 * 不承载业务状态，只展示一次解锁请求的进度和最终结果。
 */
abstract class QuickActionActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var finishJob: Job? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null

    @Volatile
    private var firstDrawSeen = false

    private lateinit var titleView: TextView
    private lateinit var badgeView: TextView
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var actionView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreen()
        setContentView(R.layout.activity_quick_action)
        bindViews()
        observeFirstDraw()
        actionView.setOnClickListener {
            finishJob?.cancel()
            openApp()
        }
        onActionReady(intent)
    }

    override fun onDestroy() {
        finishJob?.cancel()
        drawListener?.let { listener ->
            val observer = window.decorView.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnDrawListener(listener)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    protected abstract fun onActionReady(intent: Intent)

    protected fun launchAction(block: suspend CoroutineScope.() -> Unit) {
        scope.launch { block() }
    }

    protected fun setActionTitle(value: String) {
        titleView.text = value
    }

    protected fun setStatus(value: String, tone: Int = BkColor.TEXT) {
        statusView.text = value
        statusView.setTextColor(tone)
    }

    protected fun setDetail(value: String) {
        detailView.text = value
    }

    protected fun setProgressVisible(visible: Boolean) {
        progressView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    protected fun setBadge(value: String, tone: Int = BkColor.MINT) {
        badgeView.text = value
        badgeView.setTextColor(tone)
        badgeView.setBackgroundResource(
            if (tone == BkColor.CORAL || tone == BkColor.AMBER) {
                R.drawable.widget_pill_off
            } else {
                R.drawable.widget_pill_on
            }
        )
    }

    protected fun showAction(label: String = "打开 BandKey") {
        actionView.text = label
        actionView.visibility = View.VISIBLE
    }

    protected suspend fun runBackgroundUnlock(method: String, keyHint: String? = null) {
        setProgressVisible(true)
        val result = try {
            BackgroundUnlock.unlock(this@QuickActionActivity, method, keyHint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackgroundUnlockResult(
                success = false,
                state = "failed",
                message = e.message?.takeIf { it.isNotBlank() } ?: "解锁请求执行失败",
                detail = e.javaClass.simpleName
            )
        }
        runCatching {
            BackgroundUnlock.recordResult(this@QuickActionActivity, result)
            BandKeyWidgetProvider.refresh(this@QuickActionActivity)
        }
        renderResult(result)
    }

    protected fun showFailure(
        title: String,
        message: String,
        detail: String = "",
        keepAction: Boolean = true
    ) {
        setActionTitle(title)
        setBadge("BLOCKED", BkColor.CORAL)
        setStatus(message, BkColor.CORAL)
        setDetail(detail.ifBlank { "请打开 BandKey 检查配对与连接状态。" })
        setProgressVisible(false)
        if (keepAction) {
            showAction()
        }
        finishLater(6_000)
    }

    protected fun finishLater(millis: Long) {
        finishJob?.cancel()
        finishJob = scope.launch {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (!firstDrawSeen &&
                !isFinishing &&
                SystemClock.uptimeMillis() < deadline
            ) {
                delay(80)
            }
            delay(millis)
            finish()
        }
    }

    private fun observeFirstDraw() {
        val decorView = window.decorView
        val observer = decorView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (firstDrawSeen) return
                firstDrawSeen = true
                decorView.post {
                    if (observer.isAlive) {
                        observer.removeOnDrawListener(this)
                    }
                }
            }
        }
        drawListener = listener
        observer.addOnDrawListener(listener)
    }

    private fun renderResult(result: BackgroundUnlockResult) {
        setProgressVisible(false)
        when {
            result.success && result.state == "succeeded" -> {
                setActionTitle("电脑已解锁")
                setBadge("VERIFIED", BkColor.MINT)
                setStatus(result.message, BkColor.MINT)
                setDetail(
                    "通道：${result.channel.ifBlank { "本地安全通道" }} · " +
                        "请求 #${result.requestId}"
                )
                finishLater(1_800)
            }
            result.success -> {
                setActionTitle("请求已提交")
                setBadge("PENDING", BkColor.AMBER)
                setStatus(result.message, BkColor.AMBER)
                setDetail(
                    "通道：${result.channel.ifBlank { "本地安全通道" }} · " +
                        "Windows 完成登录后即可进入桌面"
                )
                finishLater(2_600)
            }
            else -> {
                setActionTitle("解锁失败")
                setBadge("BLOCKED", BkColor.CORAL)
                setStatus(result.message, BkColor.CORAL)
                setDetail(
                    if (result.detail.isNotBlank()) {
                        "原因代码：${result.detail.take(120)}"
                    } else {
                        "请检查电脑端服务、蓝牙与网络连接。"
                    }
                )
                showAction()
                finishLater(6_000)
            }
        }
    }

    private fun bindViews() {
        titleView = findViewById(R.id.quick_action_title)
        badgeView = findViewById(R.id.quick_action_badge)
        statusView = findViewById(R.id.quick_action_status)
        detailView = findViewById(R.id.quick_action_detail)
        progressView = findViewById(R.id.quick_action_progress)
        actionView = findViewById(R.id.quick_action_button)
    }

    private fun configureLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.setStatusBarColor(Color.TRANSPARENT)
        window.setNavigationBarColor(Color.TRANSPARENT)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    protected object BkColor {
        val TEXT = Color.parseColor("#EDF6FA")
        val MUTED = Color.parseColor("#8A9BAE")
        val MINT = Color.parseColor("#63F2CF")
        val AMBER = Color.parseColor("#FFB86B")
        val CORAL = Color.parseColor("#FF6B81")
    }
}

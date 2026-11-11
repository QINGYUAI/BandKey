package com.bandkey.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.os.Bundle
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BandKeyWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_NFC_AUTO = "com.bandkey.app.widget.TOGGLE_NFC_AUTO"

        private const val RC_UNLOCK = 4101
        private const val RC_OPEN = 4102
        private const val RC_TOGGLE = 4103

        fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            listOf(
                BandKeyWidgetProvider::class.java,
                BandKeyMiuiWidgetProvider::class.java
            ).forEach { providerClass ->
                val component = ComponentName(appContext, providerClass)
                val miui = providerClass == BandKeyMiuiWidgetProvider::class.java
                manager.getAppWidgetIds(component).forEach { widgetId ->
                    updateWidget(appContext, manager, widgetId, miui)
                }
            }
        }

        internal fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            miui: Boolean = false
        ) {
            val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
            val maxWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
            val maxHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val portrait = context.resources.configuration.orientation ==
                Configuration.ORIENTATION_PORTRAIT
            val currentWidth = when {
                portrait && minWidth > 0 -> minWidth
                !portrait && maxWidth > 0 -> maxWidth
                minWidth > 0 -> minWidth
                else -> maxWidth
            }
            val currentHeight = when {
                portrait && maxHeight > 0 -> maxHeight
                !portrait && minHeight > 0 -> minHeight
                maxHeight > 0 -> maxHeight
                else -> minHeight
            }
            val layoutId = layoutForSize(currentWidth, currentHeight)
            val mini = layoutId == R.layout.widget_bandkey_mini
            val small = layoutId == R.layout.widget_bandkey_small
            Log.d(
                "BandKeyWidget",
                "update widget=$widgetId min=${minWidth}x$minHeight " +
                    "max=${maxWidth}x$maxHeight " +
                    "current=${currentWidth}x$currentHeight layout=$layoutId"
            )
            val views = RemoteViews(context.packageName, layoutId)
            if (miui) {
                views.setInt(
                    android.R.id.background,
                    "setBackgroundResource",
                    R.drawable.widget_background_miui
                )
            }
            val pairing = runCatching { PairingStore(context).load() }.getOrNull()
            val nfcCount = runCatching { NfcKeyStore(context).load().size }.getOrDefault(0)
            val nfcAuto = BackgroundUnlock.isNfcAutoUnlockEnabled(context)
            val config = BackgroundUnlock.config(context)
            val paired = pairing != null && pairing.deviceId.isNotBlank()

            views.setTextViewText(
                R.id.widget_device,
                if (paired) {
                    "已配对 · ${pairing?.deviceName?.ifBlank { config.deviceName } ?: config.deviceName}"
                } else {
                    "未配对 · 打开应用完成配对"
                }
            )
            views.setTextViewText(R.id.widget_badge_on, "READY")
            views.setTextViewText(R.id.widget_badge_off, "SETUP")
            val showReadyBadge = paired && !mini
            views.setViewVisibility(
                R.id.widget_badge_on,
                if (showReadyBadge) android.view.View.VISIBLE else android.view.View.GONE
            )
            views.setViewVisibility(
                R.id.widget_badge_off,
                if (!paired && !mini) android.view.View.VISIBLE else android.view.View.GONE
            )

            views.setTextViewText(
                R.id.widget_nfc,
                "NFC 钥匙 $nfcCount 张 · 自动解锁${if (nfcAuto) "开启" else "关闭"}"
            )

            val prefs = BackgroundUnlock.prefs(context)
            val lastMessage = prefs.getString(BackgroundUnlock.KEY_LAST_MESSAGE, null)
            val lastAt = prefs.getLong(BackgroundUnlock.KEY_LAST_AT, 0L)
            views.setTextViewText(
                R.id.widget_last,
                if (!lastMessage.isNullOrBlank()) {
                    "上次 · ${formatTime(lastAt)} · ${lastMessage.take(34)}"
                } else {
                    "等待下一次解锁 · ${config.mode.uppercase(Locale.ROOT)}"
                }
            )
            if (mini) {
                views.setViewVisibility(
                    R.id.widget_nfc,
                    if (currentHeight >= 96) android.view.View.VISIBLE else android.view.View.GONE
                )
                views.setViewVisibility(R.id.widget_last, android.view.View.GONE)
            } else if (small) {
                views.setViewVisibility(R.id.widget_nfc, android.view.View.VISIBLE)
                views.setViewVisibility(
                    R.id.widget_last,
                    if (currentHeight >= 128) android.view.View.VISIBLE else android.view.View.GONE
                )
                views.setViewVisibility(
                    R.id.widget_secondary,
                    if (currentHeight >= 170) android.view.View.VISIBLE else android.view.View.GONE
                )
                views.setViewVisibility(
                    R.id.widget_center,
                    if (currentHeight >= 200) android.view.View.VISIBLE else android.view.View.GONE
                )
            }

            views.setTextViewText(
                R.id.widget_unlock,
                when {
                    mini && paired -> "解锁"
                    mini -> "配对"
                    paired -> "一键解锁"
                    else -> "去完成配对"
                }
            )
            views.setTextViewText(
                R.id.widget_toggle,
                "NFC 自动：${if (nfcAuto) "开" else "关"}"
            )
            views.setTextColor(
                R.id.widget_toggle,
                context.getColor(if (nfcAuto) R.color.bandkey_mint else R.color.bandkey_muted)
            )

            views.setOnClickPendingIntent(
                R.id.widget_unlock,
                PendingIntent.getActivity(
                    context,
                    RC_UNLOCK,
                    Intent(
                        context,
                        if (paired) QuickUnlockActivity::class.java else MainActivity::class.java
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        if (paired) {
                            putExtra("auto_start", true)
                        }
                    },
                    immutableFlags()
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_open,
                PendingIntent.getActivity(
                    context,
                    RC_OPEN,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    immutableFlags()
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_toggle,
                PendingIntent.getBroadcast(
                    context,
                    RC_TOGGLE,
                    Intent(context, BandKeyWidgetProvider::class.java).apply {
                        action = ACTION_TOGGLE_NFC_AUTO
                    },
                    immutableFlags()
                )
            )

            manager.updateAppWidget(widgetId, views)
        }

        private fun layoutForSize(minWidth: Int, minHeight: Int): Int {
            if (minWidth <= 0 || minHeight <= 0) return R.layout.widget_bandkey
            if (minHeight < 100) return R.layout.widget_bandkey_mini
            if (minHeight < 300 || minWidth < 240) return R.layout.widget_bandkey_small
            if (minWidth >= 360) return R.layout.widget_bandkey_large
            return R.layout.widget_bandkey
        }

        private fun immutableFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun formatTime(epochMillis: Long): String =
            if (epochMillis <= 0L) {
                "--:--"
            } else {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
            }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_NFC_AUTO) {
            val enabled = !BackgroundUnlock.isNfcAutoUnlockEnabled(context)
            BackgroundUnlock.setNfcAutoUnlockEnabled(context, enabled)
            refresh(context)
            Toast.makeText(
                context,
                if (enabled) {
                    "已开启 NFC 自动解锁，锁屏时贴卡即可尝试解锁"
                } else {
                    "已关闭 NFC 自动解锁"
                },
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        super.onReceive(context, intent)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }
}

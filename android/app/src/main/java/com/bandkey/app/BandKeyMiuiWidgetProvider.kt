package com.bandkey.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 小米 / HyperOS 2×2 快捷卡片。
 *
 * 小米小部件仍基于 AppWidgetProvider 和 RemoteViews，但要进入小米小部件中心，
 * 额外需要 miuiWidget 标识、独立 :widgetProvider 进程、小部件版本号和
 * 小米开放平台审核。这里单独注册 2×2 provider，避免通用 3×3 组件混用尺寸。
 */
class BandKeyMiuiWidgetProvider : AppWidgetProvider() {

    private companion object {
        const val ACTION_MIUI_APPWIDGET_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            BandKeyWidgetProvider.updateWidget(context, appWidgetManager, widgetId, miui = true)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        BandKeyWidgetProvider.updateWidget(context, appWidgetManager, appWidgetId, miui = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIUI_APPWIDGET_UPDATE) {
            val widgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(android.content.ComponentName(context, javaClass))
            val manager = AppWidgetManager.getInstance(context)
            widgetIds.forEach { widgetId ->
                BandKeyWidgetProvider.updateWidget(context, manager, widgetId, miui = true)
            }
            return
        }
        super.onReceive(context, intent)
    }
}

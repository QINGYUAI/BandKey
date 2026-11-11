package com.bandkey.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 国产 ROM 的专项设置入口。
 *
 * 这些页面没有公开 API，只能按厂商约定的 action/component 依次尝试，
 * 失败时统一回退到应用详情页，避免把用户留在无响应的按钮上。
 */
object VendorSettings {

    fun isXiaomi(): Boolean {
        val names = listOf(Build.MANUFACTURER, Build.BRAND)
        return names.any { name ->
            name.contains("xiaomi", ignoreCase = true) ||
                name.contains("redmi", ignoreCase = true) ||
                name.contains("poco", ignoreCase = true)
        }
    }

    fun isHuaweiOrHonor(): Boolean {
        val names = listOf(Build.MANUFACTURER, Build.BRAND)
        return names.any { name ->
            name.contains("huawei", ignoreCase = true) ||
                name.contains("honor", ignoreCase = true)
        }
    }

    fun openNfcSettings(context: Context): Boolean = startFirst(
        context,
        listOf(
            Intent(Settings.ACTION_NFC_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
    )

    fun openNfcPaymentSettings(context: Context): Boolean = startFirst(
        context,
        listOf(
            Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS),
            Intent(Settings.ACTION_NFC_SETTINGS)
        )
    )

    fun openAutoStart(context: Context): Boolean = startFirst(
        context,
        listOf(
            Intent("miui.intent.action.OP_AUTO_START")
                .addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            appDetails(context)
        )
    )

    fun openPermissionEditor(context: Context): Boolean = startFirst(
        context,
        listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra("extra_pkgname", context.packageName),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            ).putExtra("extra_pkgname", context.packageName),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
            ).putExtra("extra_pkgname", context.packageName),
            appDetails(context)
        )
    )

    fun openBatterySettings(context: Context): Boolean = startFirst(
        context,
        listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", context.getString(R.string.app_name)),
            appDetails(context)
        )
    )

    fun openAppDetails(context: Context): Boolean = startFirst(context, listOf(appDetails(context)))

    private fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )

    private fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val started = runCatching {
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.isSuccess
            if (started) {
                return true
            }
        }
        return false
    }
}

package com.bandkey.app

import android.content.Intent

/**
 * 桌面小组件“一键解锁”入口。
 */
class QuickUnlockActivity : QuickActionActivity() {

    override fun onActionReady(intent: Intent) {
        setActionTitle("一键解锁")
        setBadge("CONNECTING")
        setStatus("正在建立安全通道…", BkColor.TEXT)
        setDetail("将复用本机已保存的配对密钥，无需输入密码。")

        if (!BackgroundUnlock.isPaired(this)) {
            showFailure(
                title = "尚未配对",
                message = "请先打开 BandKey 完成电脑配对",
                detail = "配对后，小组件可以直接发起解锁请求。"
            )
            return
        }
        launchAction {
            runBackgroundUnlock(method = "phone")
        }
    }
}

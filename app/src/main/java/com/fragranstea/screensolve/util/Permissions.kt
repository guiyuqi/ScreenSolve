package com.fragranstea.screensolve.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 悬浮窗 / 通知权限的统一入口。
 *
 * 注意两者的申请方式完全不同：
 * - SYSTEM_ALERT_WINDOW 是特殊权限，只能跳系统设置页让用户手动开，无法弹运行时权限框；
 * - POST_NOTIFICATIONS 是 Android 13 起的普通运行时权限，可以弹框。
 */
object Permissions {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 直达本应用的"显示在其他应用上层"设置页。 */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )

    /** Android 13(API 33) 起，前台服务通知需要 POST_NOTIFICATIONS。 */
    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun canPostNotifications(context: Context): Boolean =
        !needsNotificationPermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

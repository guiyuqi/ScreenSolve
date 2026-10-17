package com.fragranstea.screensolve.capture

import android.graphics.Bitmap

/**
 * 抓帧结果在 Service 与选区 Activity 之间的交接。
 *
 * 为什么用静态持有而不是 Intent 传 Bitmap：两者同进程，全屏 Bitmap 走 Bundle 会被序列化，
 * 又慢又可能超过 Binder 的 1MB 限制直接崩（TransactionTooLargeException）。
 * 代价是进程被杀时会丢，但那时整条流程本来也断了。
 */
object FrameHolder {

    /** 刚抓到的整屏画面，供选区界面显示。 */
    @Volatile
    var frame: Bitmap? = null

    /** 用户在选区界面里确认后的裁剪结果。 */
    @Volatile
    var cropped: Bitmap? = null

    fun clear() {
        frame = null
        cropped = null
    }
}

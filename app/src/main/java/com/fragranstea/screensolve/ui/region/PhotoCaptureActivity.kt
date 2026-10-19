package com.fragranstea.screensolve.ui.region

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.capture.FrameHolder
import com.fragranstea.screensolve.ui.overlay.OverlayService
import java.util.concurrent.Executors

/**
 * 拍照模式的取景页（**应用内自建相机**，CameraX）。
 *
 * 为什么不用系统相机 App（ACTION_IMAGE_CAPTURE）：
 * 1. 用户要求"拍的照片不要进本地相册"。系统相机由各家厂商实现，有的 ROM 除了写我们给的
 *    输出文件，还会往相册再存一份，我们拦不住。自己取景的话照片只进内存，媒体库完全不经手。
 * 2. 系统相机有额外确认页（✕/✓）和一堆定制，交互不可控、也没法自动化回归。
 * 3. 少一次 Activity 往返：取景页就是工具自己的界面，拍完直接进选区。
 *
 * 拍完不落盘：Bitmap 放进 [FrameHolder] 交给服务去弹选区；只有用户框选后的题目图进历史。
 */
class PhotoCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shutter: View
    private lateinit var torchButton: TextView

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSION)
        } else {
            startCamera()
        }
    }

    private fun buildLayout(): View {
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val hint = TextView(this).apply {
            text = getString(R.string.photo_hint)
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            },
        )

        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x66000000)
            contentDescription = getString(R.string.photo_cancel)
            setOnClickListener { cancel() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = (48 * density).toInt()
                marginStart = (16 * density).toInt()
            },
        )

        // 手电（补光）开关：晚上/暗光拍纸质题目必用；没有闪光灯的设备点了会提示
        torchButton = TextView(this).apply {
            text = "💡"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x66000000)
            contentDescription = getString(R.string.photo_torch)
            setOnClickListener { toggleTorch() }
            alpha = 0.6f
        }
        root.addView(
            torchButton,
            FrameLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (48 * density).toInt()
                marginEnd = (16 * density).toInt()
            },
        )

        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
                setStroke((5 * density).toInt(), 0x88FFFFFF.toInt())
            }
            setOnClickListener { takePhoto() }
        }
        root.addView(
            shutter,
            FrameLayout.LayoutParams((76 * density).toInt(), (76 * density).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (40 * density).toInt()
            },
        )

        return root
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    // 题目图不需要超高分辨率：长边 2048 足够看清上下标，解码也快。
                    // 比例按 4:3 给（绝大多数手机传感器就是 4:3）—— 之前给的是正方形 2048×2048，
                    // 相机得去挑一个"最接近正方形"的分辨率，出来的图比例不可控。
                    .setTargetResolution(android.util.Size(2048, 1536))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                imageCapture = capture
            } catch (t: Throwable) {
                Log.e(TAG, "相机启动失败", t)
                Toast.makeText(this, R.string.photo_no_camera, Toast.LENGTH_LONG).show()
                cancel()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, R.string.photo_torch_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButton.alpha = if (torchOn) 1f else 0.6f
        torchButton.setBackgroundColor(if (torchOn) 0x99FFC107.toInt() else 0x66000000)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        shutter.isEnabled = false
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        val source = image.toBitmap()
                        val degrees = image.imageInfo.rotationDegrees.toFloat()
                        if (degrees == 0f) {
                            source
                        } else {
                            val rotated = Bitmap.createBitmap(
                                source, 0, 0, source.width, source.height,
                                Matrix().apply { postRotate(degrees) }, true,
                            )
                            if (rotated !== source) source.recycle()
                            rotated
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "照片转位图失败", t)
                        null
                    } finally {
                        image.close()
                    }

                    if (bitmap == null) {
                        runOnUiThread { photoFailed() }
                        return
                    }
                    Log.i(TAG, "拍照成功 ${bitmap.width}x${bitmap.height}")
                    runOnUiThread {
                        handled = true
                        // 只留在内存里：不写相册、也不写磁盘，用完即弃
                        FrameHolder.frame = bitmap
                        FrameHolder.cropped = null
                        OverlayService.notifyPhotoReady(this@PhotoCaptureActivity)
                        finish()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    runOnUiThread { photoFailed() }
                }
            },
        )
    }

    private fun photoFailed() {
        shutter.isEnabled = true
        Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
    }

    private fun cancel() {
        if (!handled) {
            handled = true
            OverlayService.notifyRegionCancelled(this)
        }
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.photo_permission_denied, Toast.LENGTH_LONG).show()
                cancel()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { if (torchOn) camera?.cameraControl?.enableTorch(false) }
        cameraExecutor.shutdown()
        if (isFinishing && !handled) {
            handled = true
            OverlayService.notifyRegionCancelled(this)
        }
    }

    private companion object {
        const val TAG = "PhotoCapture"
        const val REQUEST_PERMISSION = 2001
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

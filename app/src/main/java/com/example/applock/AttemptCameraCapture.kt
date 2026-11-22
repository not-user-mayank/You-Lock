package com.example.applock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

internal object AttemptCameraCapture {
    fun capture(context: Context, onFinished: (File?) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onFinished(null)
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }
        }.getOrNull()
        if (cameraId == null) {
            onFinished(null)
            return
        }

        val outputSize = runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.minByOrNull { it.width.toLong() * it.height.toLong() }
        }.getOrNull() ?: Size(640, 480)

        val thread = HandlerThread("StillAttemptCamera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 1)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var finished = false
        fun finish(file: File?) {
            if (finished) return
            finished = true
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
            onFinished(file)
        }

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val imageFile = runCatching {
                val bytes = ByteArray(image.planes[0].buffer.remaining()).also {
                    image.planes[0].buffer.get(it)
                }
                val directory = File(context.filesDir, "failed_attempt_photos")
                    .apply { mkdirs() }
                File(directory, "attempt_${System.currentTimeMillis()}.jpg").also { file ->
                    FileOutputStream(file).use { it.write(bytes) }
                }
            }.getOrNull()
            image.close()
            finish(imageFile)
        }, handler)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    val output = reader.surface
                    camera.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configuredSession: CameraCaptureSession) {
                            session = configuredSession
                            runCatching {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(output)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                }.build()
                                configuredSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureFailed(
                                        captureSession: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: android.hardware.camera2.CaptureFailure
                                    ) = finish(null)
                                }, handler)
                            }.onFailure { finish(null) }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) = finish(null)
                    }, handler)
                }

                override fun onDisconnected(camera: CameraDevice) = finish(null)
                override fun onError(camera: CameraDevice, error: Int) = finish(null)
            }, handler)
        } catch (_: SecurityException) {
            finish(null)
        } catch (_: RuntimeException) {
            finish(null)
        }
    }
}

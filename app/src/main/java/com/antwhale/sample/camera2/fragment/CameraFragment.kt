package com.antwhale.sample.camera2.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.*
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.antwhale.sample.camera2.databinding.FragmentCameraBinding
import com.antwhale.sample.camera2.utils.OrientationLiveData
import com.antwhale.sample.camera2.utils.computeExifOrientation
import com.antwhale.sample.camera2.utils.getPreviewOutputSize
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max


class CameraFragment : Fragment() {
    private val TAG = CameraFragment::class.java.simpleName
    private lateinit var binding: FragmentCameraBinding

    private lateinit var camera: CameraDevice
    private val IMAGE_BUFFER_SIZE = 60
    private val IMAGE_CAPTURE_TIMEOUT_MILLIS = 5000L

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    private val previewReaderThread = HandlerThread("previewReaderThread").apply { start() }
    private val previewReaderHandler = Handler(previewReaderThread.looper)

    val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)

    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val cameraId: String by lazy {
        val cameraIdList = cameraManager.cameraIdList
        var myCameraId = ""

        for (id in cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val cameraLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                myCameraId = id
            }
        }

        myCameraId
    }

    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    private lateinit var imageReader: ImageReader
    private lateinit var previewReader: ImageReader
    private lateinit var session: CameraCaptureSession
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        binding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(p0: SurfaceHolder) {
                Log.d(TAG, "surfaceCreated")

                val previewSize = getPreviewOutputSize(
                    binding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java
                )

                Log.d(TAG,
                    "View finder size: ${binding.viewFinder.width} x ${binding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                binding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                view.post { initializeCamera(previewSize) }
            }

            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
                Log.d(TAG, "surfaceChanged")
            }

            override fun surfaceDestroyed(p0: SurfaceHolder) {
                Log.d(TAG, "surfaceDestroyed")
            }
        })

        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner) { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            }
        }

        binding.captureButton.setOnClickListener {
            it.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")

                    //Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    if (output.extension == "jpg") {
                        val exif = androidx.exifinterface.media.ExifInterface(output.absolutePath)
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                            result.orientation.toString())
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                    }

                    lifecycleScope.launch(Dispatchers.Main) {
                        val path = output.absolutePath
                        val action =
                            CameraFragmentDirections.actionCameraFragmentToImageViewFragment(path)
                        findNavController().navigate(action)
                    }

                    it.post { it.isEnabled = true }
                }
            }
        }
    }

    private fun initializeCamera(previewImgSize: Size) = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "initializeCamera")
        camera = openCamera()

        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.height * it.width }!!

        val previewSize = Size(binding.viewFinder.width, binding.viewFinder.height)
        Log.d(TAG, "AutoFitSurfaceView size : ${previewSize.width} x ${previewSize.height}")

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, IMAGE_BUFFER_SIZE)

        previewReader =
            ImageReader.newInstance(previewImgSize.width,
                previewImgSize.height,
                ImageFormat.YUV_420_888,
                2)

        previewReader.setOnImageAvailableListener({ reader ->
            /* val image = reader.acquireNextImage()
             Log.d(TAG, "Image available in queue: ${image.timestamp}")
             imageQueue.add(image)*/

            Log.d(TAG, "setOnImageAvailableListener")
            val image: Image = previewReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            Log.d(TAG, "Image available in queue: ${image.timestamp}")

////            imageQueue.add(image)

//            val plane = image.planes[0]
//            val buffer: ByteBuffer = plane.buffer
//            val bytes = ByteArray(buffer.capacity())
//            buffer.get(bytes)
//            val previewImg = BitmapFactory.decodeByteArray(bytes, 0, buffer.capacity())
//            Log.d(TAG, "previewImg size: ${previewImg.width} x ${previewImg.height}")
//
//            val scaleRatio: Float = max((previewSize.width.toFloat() / previewImg.height.toFloat()) , (previewSize.height.toFloat() / previewImg.width.toFloat()))
//
//            //90도 회전시키고 확대
//            val matrix = Matrix()
//            matrix.postRotate(90f)
//            matrix.postScale(scaleRatio, scaleRatio)
//
//            val rotatedBitmap = Bitmap.createBitmap(previewImg,
//                0,
//                0,
//                previewImg.width,
//                previewImg.height,
//                matrix,
//                true)
//
//
//            val targetWidth = rotatedBitmap.width
//            val targetHeight = rotatedBitmap.height
//
//            val widthDeviation = targetWidth - previewImg.height
//            val heightDeviation = targetHeight - previewImg.width
//
//            Log.d(TAG, "scaleRatio: $scaleRatio, targetWidth: $targetWidth, targetHeight: $targetHeight, widthDeviation: $widthDeviation, heightDeviation: $heightDeviation")

            image.close()
//            if (rotatedBitmap != null) {
//                // This gets the canvas for the same mTextureView we would have connected to the
//                // Camera2 preview directly above.
//                val canvas: Canvas = binding.textureView.lockCanvas() ?: return@setOnImageAvailableListener
//
//                val colorTransform = floatArrayOf(0f, 0f, 0f, 0f, 0f,
//                    .35f, .45f, .25f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
//                val colorMatrix = ColorMatrix()
//                colorMatrix.set(colorTransform) //Apply the monochrome green
//                val colorFilter = ColorMatrixColorFilter(colorMatrix)
//                val paint = Paint()
//                paint.colorFilter = colorFilter
//
//                canvas.drawBitmap(rotatedBitmap, 0f, 0f, paint)
//
//                binding.textureView.unlockCanvasAndPost(canvas)
//            }

        }, previewReaderHandler)


        val targets =
            listOf(binding.viewFinder.holder.surface, imageReader.surface, previewReader.surface)

        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply {
                addTarget(binding.viewFinder.holder.surface)
                addTarget(previewReader.surface)
            }

        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCancellableCoroutine { cont ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.d(TAG, "onOpened")
                cont.resume(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.d(TAG, "onDisconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, cameraHandler)
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null,
    ): CameraCaptureSession = suspendCoroutine { cont ->
        Log.d(TAG, "createCameraPreviewSession: ")

        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "onConfigured")
                cont.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest =
            session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                .apply { addTarget(imageReader.surface) }

        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            //here
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                Log.d(TAG, "onCaptureStarted")
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {
                        //Dequeue images while timestamps don't match
                        val image = imageQueue.take()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        //Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        //Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored =
                            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        cont.resume(CombinedCaptureResult(
                            image, result, exifOrientation, imageReader.imageFormat
                        ))

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.foramt) {
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }

                try {
                    val output = createFile(requireContext(), "jpg")
                    FileOutputStream(output).use { it.write(bytes) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()

        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroyView() {

        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
        previewReaderThread.quitSafely()

        imageReader.setOnImageAvailableListener(null, null)
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val foramt: Int,
        ) : Closeable {
            override fun close() = image.close()
        }

        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }
    }


}
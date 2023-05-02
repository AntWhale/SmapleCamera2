package com.antwhale.sample.camera2.fragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavArgs
import androidx.navigation.NavController
import androidx.navigation.fragment.navArgs
import com.antwhale.sample.camera2.R
import com.antwhale.sample.camera2.databinding.FragmentImageViewBinding
import com.antwhale.sample.camera2.databinding.FragmentMainBinding
import com.antwhale.sample.camera2.utils.decodeExifOrientation
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import kotlin.math.max

class ImageViewFragment : Fragment() {
    val TAG = ImageViewFragment::class.java.simpleName
    private lateinit var binding: FragmentImageViewBinding

    private lateinit var bitmapTransformation: Matrix

    private val bitmapOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        // Keep Bitmaps at less than 1 MP
        if (max(outHeight, outWidth) > DOWNSAMPLE_SIZE) {
            val scaleFactorX = outWidth / DOWNSAMPLE_SIZE + 1
            val scaleFactorY = outHeight / DOWNSAMPLE_SIZE + 1
            inSampleSize = max(scaleFactorX, scaleFactorY)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        binding = FragmentImageViewBinding.inflate(inflater, container, false)


        // Inflate the layout for this fragment
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args : ImageViewFragmentArgs by navArgs<ImageViewFragmentArgs>()
        val filePath = args.filePath
        Log.d(TAG, "filePath: $filePath")

        bitmapTransformation = decodeExifOrientation(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90)

        val imgFile = File(filePath)
        if(imgFile.exists()) Log.d(TAG, "imgFile exists")

        lifecycleScope.launch(Dispatchers.IO) {


            val imgByteArray = loadInputBuffer(imgFile)
            val bitmapImg = decodeBitmap(imgByteArray)


            withContext(Dispatchers.Main) {
                binding.imageView.setImageBitmap(bitmapImg)
            }
        }

//        binding.imageView.setImageBitmap(decodeBitmap(imgByteArray))



        /*if(imgFile.exists()){
            Log.d("", "img file exists")
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            binding.imageView.setImageBitmap(bitmap)
        }*/
    }

    private fun loadInputBuffer(inputFile: File): ByteArray {

        return BufferedInputStream(inputFile.inputStream()).let { stream ->
            ByteArray(stream.available()).also {
                stream.read(it)
                stream.close()
            }
        }
    }

    private fun decodeBitmap(buffer : ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(buffer, 0, buffer.size, bitmapOptions)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, bitmapTransformation, true)
    }




    companion object {
        private const val DOWNSAMPLE_SIZE: Int = 1024  // 1MP


    }
}
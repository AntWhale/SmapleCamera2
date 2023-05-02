package com.antwhale.sample.camera2

import android.Manifest
import android.Manifest.permission.CAMERA
import android.Manifest.permission_group.CAMERA
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import com.antwhale.sample.camera2.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        setContentView(binding.root)

        val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            when(it) {
                true -> Toast.makeText(this,"권한 허가",Toast.LENGTH_SHORT).show()
                false -> Toast.makeText(this,"권한 거부",Toast.LENGTH_SHORT).show()
            }
        }
        val cameraPermission = Manifest.permission.CAMERA

        requestPermission.launch(cameraPermission)

    }
}
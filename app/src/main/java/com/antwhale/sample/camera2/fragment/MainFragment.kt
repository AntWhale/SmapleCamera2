package com.antwhale.sample.camera2.fragment

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.NavHostFragment
import com.antwhale.sample.camera2.R
import com.antwhale.sample.camera2.databinding.FragmentMainBinding

class MainFragment : Fragment() {
    private val TAG = MainFragment::class.java.simpleName
    private lateinit var binding: FragmentMainBinding
    private lateinit var navController: NavController

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        navController = Navigation.findNavController(requireActivity(), R.id.fragmentContainer)
        binding.mainFragment = this

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


    }

    fun navigateToCameraFragment(view: View) {
        Log.d(TAG, "navigateToCameraFragment")
        navController.navigate(R.id.action_mainFragment_to_cameraFragment)
    }
}
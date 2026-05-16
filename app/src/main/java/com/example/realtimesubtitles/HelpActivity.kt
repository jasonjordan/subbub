package com.example.realtimesubtitles

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.realtimesubtitles.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.helpBackButton.setOnClickListener {
            finish()
        }
    }
}

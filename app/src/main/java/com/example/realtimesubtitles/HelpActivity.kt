package com.example.realtimesubtitles

import android.os.Bundle
import android.text.Html
import androidx.appcompat.app.AppCompatActivity
import com.example.realtimesubtitles.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val html = resources.openRawResource(R.raw.help_text)
            .bufferedReader()
            .use { it.readText() }
            .removePrefix("<![CDATA[\n")
            .removeSuffix("]]>\n")

        binding.helpText.text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }

        binding.helpBackButton.setOnClickListener {
            finish()
        }
    }
}

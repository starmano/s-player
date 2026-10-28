package com.starmano.splayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.starmano.splayer.databinding.ActivityMainBinding

/** Launcher screen: explains the Stremio setup and can play any stream URL for testing. */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.playButton.setOnClickListener { play() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                play()
                true
            } else {
                false
            }
        }
    }

    private fun play() {
        val url = binding.urlInput.text.toString().trim()
        val uri = Uri.parse(url)
        if (uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            binding.urlInput.error = getString(R.string.invalid_url)
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, uri, this, PlayerActivity::class.java))
    }
}

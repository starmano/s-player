package com.starmano.splayer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.starmano.splayer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher screen: explains the Stremio setup, can play any stream URL for testing, and checks
 * for a newer version of the app whenever it's opened.
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var downloadingUpdate = false

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

        @Suppress("DEPRECATION")
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        binding.version.text = getString(R.string.version_label, versionName, Updates.currentBuild(this))
        binding.updateButton.setOnClickListener { installUpdate() }
    }

    override fun onStart() {
        super.onStart()
        checkForUpdate()
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

    private fun checkForUpdate() {
        if (downloadingUpdate) return
        binding.updateStatus.setText(R.string.update_checking)
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { runCatching { Updates.latestBuild() }.getOrNull() }
            val newer = latest != null && latest > Updates.currentBuild(this@MainActivity)
            binding.updateStatus.setText(
                when {
                    latest == null -> R.string.update_check_failed
                    newer -> R.string.update_available
                    else -> R.string.update_up_to_date
                },
            )
            binding.updateButton.isVisible = newer
        }
    }

    private fun installUpdate() {
        downloadingUpdate = true
        binding.updateButton.isEnabled = false
        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) {
                runCatching {
                    Updates.download(this@MainActivity) { percent ->
                        runOnUiThread { showDownloadProgress(percent) }
                    }
                }
            }
            downloadingUpdate = false
            binding.updateButton.isEnabled = true
            binding.updateButton.setText(R.string.update_install)
            apk.onSuccess { file ->
                try {
                    Updates.install(this@MainActivity, file)
                } catch (e: ActivityNotFoundException) {
                    binding.updateStatus.setText(R.string.update_install_failed)
                }
            }.onFailure {
                binding.updateStatus.setText(R.string.update_download_failed)
            }
        }
    }

    private fun showDownloadProgress(percent: Int) {
        binding.updateButton.text = if (percent >= 0) {
            getString(R.string.update_downloading_percent, percent)
        } else {
            getString(R.string.update_downloading)
        }
    }
}

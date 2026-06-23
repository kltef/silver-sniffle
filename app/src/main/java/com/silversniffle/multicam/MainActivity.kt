package com.silversniffle.multicam

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.silversniffle.multicam.camera.CameraController
import com.silversniffle.multicam.camera.CameraEnumerator
import com.silversniffle.multicam.camera.CameraTileState
import com.silversniffle.multicam.databinding.ActivityMainBinding
import com.silversniffle.multicam.ui.CameraGridAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private val tiles = mutableListOf<CameraGridAdapter.Tile>()
    private lateinit var adapter: CameraGridAdapter

    /** Delay between successive camera opens so the framework processes them in order. */
    private val staggerMs = 150L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startAllCameras()
            } else {
                tiles.forEach { it.state = CameraTileState.Error(getString(R.string.permission_denied)) }
                adapter.notifyDataSetChanged()
                updateBanner()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildTiles()
        setupRecyclerView()
        updateBanner()
    }

    private fun buildTiles() {
        val enumerator = CameraEnumerator(this)
        // Estimate a tile size (half the screen width for a 2-column grid) to pick preview sizes.
        val metrics = resources.displayMetrics
        val tilePx = Size(metrics.widthPixels / 2, metrics.widthPixels / 2 * 3 / 4)
        val cameras = enumerator.enumerate(tilePx)

        tiles.clear()
        cameras.forEach { info ->
            lateinit var tile: CameraGridAdapter.Tile
            val controller = CameraController(
                context = this,
                info = info,
                mainHandler = mainHandler,
            ) { state ->
                tile.state = state
                val index = tiles.indexOf(tile)
                if (index >= 0) adapter.notifyItemChanged(index)
                updateBanner()
            }
            tile = CameraGridAdapter.Tile(info, CameraTileState.Idle, controller)
            tiles.add(tile)
        }
    }

    private fun setupRecyclerView() {
        adapter = CameraGridAdapter(tiles) { tile ->
            // Retry: closing and re-opening this camera may free/grab a hardware slot.
            tile.controller.close()
            tile.state = CameraTileState.Idle
            adapter.notifyItemChanged(tiles.indexOf(tile))
            tile.controller.start()
        }
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, spanCount())
            adapter = this@MainActivity.adapter
            // Keep every tile materialized so live preview surfaces are never recycled.
            setItemViewCacheSize(tiles.size.coerceAtLeast(1))
            (layoutManager as GridLayoutManager).isItemPrefetchEnabled = false
        }
    }

    private fun spanCount(): Int {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val base = when {
            tiles.size <= 1 -> 1
            tiles.size <= 4 -> 2
            else -> 3
        }
        return if (landscape) base + 1 else base
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startAllCameras()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacksAndMessages(null)
        tiles.forEach { it.controller.close() }
    }

    private fun startAllCameras() {
        tiles.forEachIndexed { index, tile ->
            mainHandler.postDelayed({ tile.controller.start() }, index * staggerMs)
        }
    }

    private fun updateBanner() {
        val enumerator = CameraEnumerator(this)
        val streaming = tiles.count { it.state is CameraTileState.Streaming }
        val total = tiles.size

        val concurrent = enumerator.concurrentCameraIds
        val concurrentText = when {
            concurrent.isEmpty() && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ->
                getString(R.string.concurrent_needs_android_11)
            concurrent.isEmpty() ->
                getString(R.string.concurrent_none)
            else -> concurrent.joinToString(", ") { set ->
                set.sorted().joinToString(prefix = "{", postfix = "}", separator = ",")
            }
        }

        binding.bannerTitle.text = getString(R.string.banner_title, total)
        binding.bannerStreaming.text = getString(R.string.banner_streaming, streaming, total)
        binding.bannerConcurrent.text = getString(R.string.banner_concurrent, concurrentText)
    }
}

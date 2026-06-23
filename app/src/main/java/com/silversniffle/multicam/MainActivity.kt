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

    /** How long to dwell on a camera once it is streaming, before moving to the next. */
    private val dwellMs = 2500L

    /** Shorter dwell when a camera fails to open, so a bad camera doesn't stall the loop. */
    private val errorDwellMs = 1200L

    /** Hard cap on waiting for a camera to open before giving up and advancing. */
    private val openTimeoutMs = 4000L

    private var cycling = false
    private var cycleIndex = -1
    private var pinnedIndex: Int? = null
    private val advanceRunnable = Runnable { advanceCycle() }

    private var concurrentCameraIds: Set<Set<String>> = emptySet()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCycle()
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
        concurrentCameraIds = enumerator.concurrentCameraIds
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
                onTileStateChanged(index, state)
                updateBanner()
            }
            tile = CameraGridAdapter.Tile(info, CameraTileState.Idle, controller)
            tiles.add(tile)
        }
    }

    /** Refine the cycle timing based on how the currently-active camera resolved. */
    private fun onTileStateChanged(index: Int, state: CameraTileState) {
        if (!cycling || index != cycleIndex) return
        when (state) {
            is CameraTileState.Streaming -> scheduleAdvance(dwellMs)
            is CameraTileState.Error -> scheduleAdvance(errorDwellMs)
            else -> Unit
        }
    }

    private fun setupRecyclerView() {
        adapter = CameraGridAdapter(
            tiles = tiles,
            onTileClick = { tile -> togglePin(tiles.indexOf(tile)) },
            onRetry = { tile ->
                // Manual restart of a single camera (e.g. after an error while pinned).
                tile.controller.close()
                tile.state = CameraTileState.Idle
                adapter.notifyItemChanged(tiles.indexOf(tile))
                tile.controller.start()
            },
        )
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
            startCycle()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        cycling = false
        mainHandler.removeCallbacksAndMessages(null)
        tiles.forEach { it.controller.close() }
    }

    /**
     * Cycle method: only one camera is open at a time (sidestepping the device's concurrent-open
     * limit), advancing through every camera on a timer so all of them get shown live in turn.
     */
    private fun startCycle() {
        if (tiles.isEmpty() || pinnedIndex != null) return
        cycling = true
        cycleIndex = -1
        advanceCycle()
    }

    private fun advanceCycle() {
        if (!cycling || tiles.isEmpty()) return
        // Close the camera we were showing.
        tiles.getOrNull(cycleIndex)?.let { current ->
            current.controller.close()
            current.state = CameraTileState.Idle
            adapter.notifyItemChanged(cycleIndex)
        }
        // Open the next one.
        cycleIndex = (cycleIndex + 1) % tiles.size
        tiles[cycleIndex].controller.start()
        updateBanner()
        // Fallback timer in case the camera never reports a state (stuck opening); the per-tile
        // state callback (onTileStateChanged) will shorten/replace this once it streams or fails.
        scheduleAdvance(openTimeoutMs)
    }

    private fun scheduleAdvance(delay: Long) {
        mainHandler.removeCallbacks(advanceRunnable)
        mainHandler.postDelayed(advanceRunnable, delay)
    }

    /** Tap a tile to pin it (pause cycling on that camera); tap again to resume cycling. */
    private fun togglePin(index: Int) {
        if (index !in tiles.indices) return
        if (pinnedIndex == index) {
            // Unpin → resume cycling from this camera.
            tiles[index].pinned = false
            adapter.notifyItemChanged(index)
            pinnedIndex = null
            cycling = true
            scheduleAdvance(dwellMs)
            updateBanner()
            return
        }
        // Pin a new tile: stop cycling, close everything else, keep this one open.
        cycling = false
        mainHandler.removeCallbacks(advanceRunnable)
        pinnedIndex?.let { tiles.getOrNull(it)?.pinned = false }
        tiles.forEachIndexed { i, tile ->
            if (i != index) {
                tile.controller.close()
                if (tile.state != CameraTileState.Idle) {
                    tile.state = CameraTileState.Idle
                }
            }
        }
        pinnedIndex = index
        cycleIndex = index
        tiles[index].pinned = true
        tiles[index].controller.start()
        adapter.notifyDataSetChanged()
        updateBanner()
    }

    private fun updateBanner() {
        val total = tiles.size

        val statusText = pinnedIndex?.let { idx ->
            getString(R.string.cycle_pinned, tiles[idx].info.cameraId)
        } ?: tiles.getOrNull(cycleIndex)?.let { current ->
            getString(R.string.cycle_now_showing, current.info.cameraId, cycleIndex + 1, total)
        } ?: getString(R.string.banner_streaming, 0, total)

        val concurrent = concurrentCameraIds
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
        binding.bannerStreaming.text = statusText
        binding.bannerConcurrent.text = getString(R.string.banner_concurrent, concurrentText)
    }
}

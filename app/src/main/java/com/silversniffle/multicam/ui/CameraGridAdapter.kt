package com.silversniffle.multicam.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.silversniffle.multicam.camera.CameraController
import com.silversniffle.multicam.camera.CameraInfo
import com.silversniffle.multicam.camera.CameraTileState
import com.silversniffle.multicam.databinding.ItemCameraTileBinding

/**
 * Renders one tile per camera. Each tile is backed by a persistent [CameraController]; the
 * adapter never recycles tiles (the host keeps the view cache >= item count), so a tile's
 * live preview surface is not torn down behind the controller's back.
 */
class CameraGridAdapter(
    private val tiles: List<Tile>,
    private val onTileClick: (Tile) -> Unit,
    private val onRetry: (Tile) -> Unit,
) : RecyclerView.Adapter<CameraGridAdapter.TileViewHolder>() {

    /** Mutable per-camera UI record. [state] is updated in place and re-rendered via notify. */
    class Tile(
        val info: CameraInfo,
        var state: CameraTileState,
        val controller: CameraController,
        /** True when the user has pinned this tile (cycling paused on it). */
        var pinned: Boolean = false,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileViewHolder {
        val binding = ItemCameraTileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TileViewHolder(binding)
    }

    override fun getItemCount(): Int = tiles.size

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) {
        val tile = tiles[position]
        holder.bind(tile)
        tile.controller.bind(holder.binding.preview)
    }

    inner class TileViewHolder(
        val binding: ItemCameraTileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tile: Tile) {
            binding.label.text = tile.info.displayLabel
            binding.badge.visibility =
                if (tile.info.concurrentSupported) View.VISIBLE else View.GONE
            renderState(tile)
            renderChip(tile)
            binding.retryButton.setOnClickListener { onRetry(tile) }
            binding.root.setOnClickListener { onTileClick(tile) }
        }

        private fun renderChip(tile: Tile) {
            val ctx = binding.root.context
            when {
                tile.pinned -> {
                    binding.statusChip.visibility = View.VISIBLE
                    binding.statusChip.text =
                        ctx.getString(com.silversniffle.multicam.R.string.chip_pinned)
                    binding.statusChip.setBackgroundColor(0xCCB26A00.toInt())
                }
                tile.state is CameraTileState.Streaming -> {
                    binding.statusChip.visibility = View.VISIBLE
                    binding.statusChip.text =
                        ctx.getString(com.silversniffle.multicam.R.string.chip_live)
                    binding.statusChip.setBackgroundColor(0xCC2E7D32.toInt())
                }
                else -> binding.statusChip.visibility = View.GONE
            }
        }

        private fun renderState(tile: Tile) {
            when (val state = tile.state) {
                is CameraTileState.Streaming -> {
                    binding.overlay.visibility = View.GONE
                }
                is CameraTileState.Opening -> {
                    binding.overlay.visibility = View.VISIBLE
                    binding.overlayMessage.text =
                        binding.root.context.getString(
                            com.silversniffle.multicam.R.string.opening
                        )
                    binding.retryButton.visibility = View.GONE
                }
                is CameraTileState.Idle -> {
                    binding.overlay.visibility = View.VISIBLE
                    binding.overlayMessage.text =
                        binding.root.context.getString(
                            com.silversniffle.multicam.R.string.idle
                        )
                    binding.retryButton.visibility = View.GONE
                }
                is CameraTileState.Error -> {
                    binding.overlay.visibility = View.VISIBLE
                    binding.overlayMessage.text = state.reason
                    binding.retryButton.visibility = View.VISIBLE
                }
            }
        }
    }
}

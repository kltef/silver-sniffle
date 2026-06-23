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
    private val onRetry: (Tile) -> Unit,
) : RecyclerView.Adapter<CameraGridAdapter.TileViewHolder>() {

    /** Mutable per-camera UI record. [state] is updated in place and re-rendered via notify. */
    class Tile(
        val info: CameraInfo,
        var state: CameraTileState,
        val controller: CameraController,
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
            binding.retryButton.setOnClickListener { onRetry(tile) }
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

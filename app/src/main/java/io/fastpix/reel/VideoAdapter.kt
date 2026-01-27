package io.fastpix.reel

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.fastpix.media3.databinding.ItemVideoBinding
import io.fastpix.reelapp.VideoItem

/**
 * RecyclerView Adapter for vertical video feed (Instagram Reels-like)
 * Each item contains a full-screen video player
 */
class VideoAdapter(
    private val videos: List<VideoItem>,
    private val onItemBind: (ViewHolder, VideoItem, Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        // Set item height to full screen
        val displayMetrics = parent.context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        binding.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            screenHeight
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val video = videos[position]
        onItemBind(holder, video, position)
    }

    override fun getItemCount(): Int = videos.size

    /**
     * ViewHolder for each video item
     */
    class ViewHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)
}


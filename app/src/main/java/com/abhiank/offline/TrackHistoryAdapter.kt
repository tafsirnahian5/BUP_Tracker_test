package com.abhiank.offline

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackHistoryAdapter(
    private var tracks: List<RecordedTrack>
) : RecyclerView.Adapter<TrackHistoryAdapter.TrackHistoryViewHolder>() {

    fun updateTracks(newTracks: List<RecordedTrack>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackHistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recorded_track, parent, false)
        return TrackHistoryViewHolder(view)
    }

    override fun getItemCount(): Int = tracks.size

    override fun onBindViewHolder(holder: TrackHistoryViewHolder, position: Int) {
        holder.bind(tracks[position])
    }

    class TrackHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.trackTitle)
        private val subtitleText: TextView = itemView.findViewById(R.id.trackSubtitle)

        fun bind(track: RecordedTrack) {
            titleText.text = track.displayName
            val context = itemView.context
            val metadata = context.getString(
                R.string.history_track_metadata,
                track.lastModified.asDisplayString(),
                track.sizeBytes.asReadableSize()
            )
            subtitleText.text = metadata
            itemView.contentDescription = context.getString(
                R.string.history_track_content_description,
                track.displayName,
                track.lastModified.asDisplayString(),
                track.sizeBytes.asReadableSize()
            )
        }
    }
}

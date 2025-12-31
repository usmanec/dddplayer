package top.rootu.dddplayer.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.MediaItem

class PlaylistAdapter(
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    private var items: List<MediaItem> = emptyList()
    private var currentPlayingIndex: Int = -1

    fun submitList(newItems: List<MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setCurrentIndex(index: Int) {
        val oldIndex = currentPlayingIndex
        currentPlayingIndex = index
        notifyItemChanged(oldIndex)
        notifyItemChanged(currentPlayingIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position, position == currentPlayingIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.item_poster)
        private val placeholder: TextView = itemView.findViewById(R.id.item_poster_placeholder)
        private val title: TextView = itemView.findViewById(R.id.item_title)
        private val progress: ProgressBar = itemView.findViewById(R.id.item_progress)

        init {
            itemView.setOnClickListener { onItemClick(adapterPosition) }
        }

        fun bind(item: MediaItem, position: Int, isPlaying: Boolean) {
            // 1. Заголовок: "1. Название"
            val displayTitle = item.title ?: item.filename ?: "Video ${position + 1}"
            title.text = "${position + 1}. $displayTitle"

            // 2. Выделение текущего
            if (isPlaying) {
                title.setTextColor(Color.parseColor("#FF202020")) // Акцентный цвет
                itemView.setBackgroundResource(R.drawable.selector_menu_item_bg) // Можно сделать активный фон
                itemView.isSelected = true
            } else {
                title.setTextColor(Color.WHITE)
                itemView.isSelected = false
            }

            // 3. Постер
            if (item.posterUri != null) {
                poster.isVisible = true
                placeholder.isVisible = false
                poster.load(item.posterUri) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(8f))
                    error(android.R.color.transparent) // Если ошибка, покажем placeholder
                    listener(onError = { _, _ ->
                        poster.isVisible = false
                        placeholder.isVisible = true
                    })
                }
            } else {
                poster.isVisible = false
                placeholder.isVisible = true
            }

            // Номер в заглушке
            placeholder.text = (position + 1).toString()

            // 4. Прогресс
            // В идеале прогресс нужно брать из БД.
            // Пока что, если это текущий трек, можно было бы обновлять,
            // но в списке обычно показывают сохраненный прогресс.
            // Если у вас есть сохраненный прогресс в MediaItem, используйте его.
            // Для примера ставим 0 или сохраненный startPositionMs (если это resume)
            // Чтобы сделать красиво, нужно прокидывать прогресс из БД в MediaItem при загрузке.
            progress.progress = 0
            progress.isVisible = false // Пока скрываем, если нет данных
        }
    }
}
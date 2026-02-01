package top.rootu.dddplayer.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.transform.RoundedCornersTransformation
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.MediaItem

class PlaylistAdapter(
    private val onItemClick: (Int) -> Unit
) : ListAdapter<MediaItem, PlaylistAdapter.ViewHolder>(MediaItemDiffCallback()) {

    private var currentPlayingIndex: Int = -1

    fun setCurrentIndex(index: Int) {
        val oldIndex = currentPlayingIndex
        currentPlayingIndex = index

        if (oldIndex in 0 until itemCount) {
            notifyItemChanged(oldIndex)
        }
        if (currentPlayingIndex in 0 until itemCount) {
            notifyItemChanged(currentPlayingIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position, position == currentPlayingIndex)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.item_poster)
        private val placeholder: TextView = itemView.findViewById(R.id.item_poster_placeholder)
        private val numberBadge: TextView = itemView.findViewById(R.id.item_number_badge)
        private val title: TextView = itemView.findViewById(R.id.item_title)
        private val progress: ProgressBar = itemView.findViewById(R.id.item_progress)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(pos)
                }
            }
        }

        fun bind(item: MediaItem, position: Int, isPlaying: Boolean) {
            // 1. Заголовок
            val displayTitle = item.title ?: item.filename ?: "Video ${position + 1}"
            title.text = displayTitle

            // 2. Выделение текущего
            if (isPlaying) {
                title.setTextColor("#000000".toColorInt())
                itemView.isSelected = true
            } else {
                title.setTextColor(Color.WHITE)
                itemView.isSelected = false
            }

            // 3. Постер и Номер
            val numberText = (position + 1).toString()

            // Важно: очищаем слушатели предыдущего запроса
            poster.dispose()

            if (item.posterUri != null) {
                // Сразу ставим заглушку, чтобы пользователь видел номер
                placeholder.text = numberText
                placeholder.isVisible = true
                numberBadge.isVisible = false

                // Постер делаем невидимым, но оставляем в layout (INVISIBLE)
                poster.setImageDrawable(null)
                poster.isVisible = true // Пусть будет виден, но пуст

                poster.load(item.posterUri) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(4f))
                    listener(
                        onSuccess = { _, _ ->
                            // Картинка есть -> скрываем заглушку, показываем бейдж
                            placeholder.isVisible = false
                            numberBadge.text = numberText
                            numberBadge.isVisible = true
                        },
                        onError = { _, _ ->
                            // Ошибка -> убеждаемся, что заглушка видна
                            placeholder.isVisible = true
                            numberBadge.isVisible = false
                        }
                    )
                }
            } else {
                // Нет URI: просто показываем заглушку
                placeholder.text = numberText
                placeholder.isVisible = true
                poster.isVisible = false
                numberBadge.isVisible = false
                poster.setImageDrawable(null)
            }

            // 4. Прогресс
            // В идеале прогресс нужно брать из БД.
            // Пока что, если это текущий трек, можно было бы обновлять,
            // но в списке обычно показывают сохраненный прогресс.
            // Если у вас есть сохраненный прогресс в MediaItem, используйте его.
            // Для примера ставим 0 или сохраненный startPositionMs (если это resume)
            // Чтобы сделать красиво, нужно прокидывать прогресс из БД в MediaItem при загрузке.
            progress.progress = 0
            progress.isVisible = false
        }
    }

    class MediaItemDiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            // Cравниваем по уникальному UUID, даже если ссылки одинаковые
            return oldItem.uuid == newItem.uuid
        }

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem == newItem
        }
    }
}
package top.rootu.dddplayer.ui.adapter

import android.graphics.Color
import android.view.KeyEvent
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
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.MediaItem

class PlaylistAdapter(
    private val onLoopRequest: (Int) -> Unit, // Колбэк для зацикливания
    private val onItemClick: (Int) -> Unit
) : ListAdapter<MediaItem, PlaylistAdapter.ViewHolder>(MediaItemDiffCallback()) {

    private var playingItemUuid: String? = null
    private var showIndexBadge: Boolean = true
    var shouldLoop: Boolean = false // Флаг зацикливания

    fun setPlayingItemUuid(uuid: String?) {
        playingItemUuid = uuid
        notifyDataSetChanged() // Перерисовываем список
    }

    // Метод для обновления настройки извне
    fun setShowIndexBadge(show: Boolean) {
        if (showIndexBadge != show) {
            showIndexBadge = show
            notifyDataSetChanged() // Перерисовываем список, чтобы скрыть/показать цифры
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, item.uuid == playingItemUuid)
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

            // ЦЕНТРИРОВАНИЕ ПРИ ФОКУСЕ
            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val rv = v.parent as? RecyclerView
                        // Используем плавную прокрутку только если элемент не в центре
                        rv?.smoothScrollToPosition(pos)
                    }
                }
            }

            // ЛОГИКА ЗАЦИКЛИВАНИЯ (на уровне элемента)
            itemView.setOnKeyListener { _, keyCode, event ->
                if (shouldLoop && event.action == KeyEvent.ACTION_DOWN) {
                    val pos = bindingAdapterPosition
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos == itemCount - 1) {
                        onLoopRequest(0) // Прыгаем в начало
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos == 0) {
                        onLoopRequest(itemCount - 1) // Прыгаем в конец
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        fun bind(item: MediaItem, isPlaying: Boolean) {
            title.text = item.title ?: item.filename ?: "Video"
            if (isPlaying) {
                title.setTextColor("#000000".toColorInt())
                itemView.isSelected = true
            } else {
                title.setTextColor(Color.WHITE)
                itemView.isSelected = false
            }

            val numberText = (item.absoluteIndex + 1).toString()

            // Важно: очищаем слушатели предыдущего запроса
            poster.dispose()

            if (item.posterUri != null) {
                // Сразу ставим заглушку, чтобы пользователь видел номер
                placeholder.text = numberText
                placeholder.isVisible = true
                numberBadge.isVisible = false

                // Постер делаем невидимым, но оставляем в layout (INVISIBLE)
                poster.setImageDrawable(null)
                poster.isVisible = true

                poster.load(item.posterUri) {
                    crossfade(true)
                    listener(
                        onSuccess = { _, _ ->
                            // Картинка есть -> скрываем заглушку, показываем бейдж
                            placeholder.isVisible = false
                            numberBadge.text = numberText
                            numberBadge.isVisible = showIndexBadge
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
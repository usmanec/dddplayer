package top.rootu.dddplayer.ui.adapter

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R

class OptionsAdapter : ListAdapter<OptionsAdapter.OptionItem, OptionsAdapter.ViewHolder>(OptionDiffCallback()) {

    private var selectedIndex: Int = -1

    /**
     * Внутренняя модель для обеспечения уникальности строк.
     * id формируется на основе индекса в списке.
     */
    data class OptionItem(val id: Long, val text: String)

    /**
     * Метод для приема списка строк.
     * Преобразует строки в OptionItem с уникальными ID.
     */
    fun submitData(items: List<String>) {
        val mappedItems = items.mapIndexed { index, text ->
            OptionItem(index.toLong(), text)
        }
        submitList(mappedItems)
    }

    fun setSelection(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index

        if (oldIndex in 0 until itemCount) {
            notifyItemChanged(oldIndex)
        }
        if (selectedIndex in 0 until itemCount) {
            notifyItemChanged(selectedIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting_option, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position).text, position == selectedIndex)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.option_text)

        fun bind(text: String, isSelected: Boolean) {
            textView.text = text
            textView.isSelected = isSelected

            // Жирный шрифт и немного увеличенный размер для выбранного
            if (isSelected) {
                textView.setTypeface(null, Typeface.BOLD)
                textView.textSize = 14f
                textView.alpha = 1.0f
            } else {
                textView.setTypeface(null, Typeface.NORMAL)
                textView.textSize = 12f
                textView.alpha = 0.8f
            }
        }
    }

    class OptionDiffCallback : DiffUtil.ItemCallback<OptionItem>() {
        override fun areItemsTheSame(oldItem: OptionItem, newItem: OptionItem): Boolean {
            // Сравниваем по ID (который равен индексу),
            // это гарантирует корректную работу даже с одинаковыми строками
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: OptionItem, newItem: OptionItem): Boolean {
            return oldItem == newItem
        }
    }
}
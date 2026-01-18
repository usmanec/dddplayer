package top.rootu.dddplayer.ui.adapter

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R

class OptionsAdapter : RecyclerView.Adapter<OptionsAdapter.ViewHolder>() {

    private var items: List<String> = emptyList()
    private var selectedIndex: Int = -1

    fun submitList(newItems: List<String>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setSelection(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index
        notifyItemChanged(oldIndex)
        notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting_option, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = items.size

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
}
package top.rootu.dddplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.MenuItem

class SideMenuAdapter(
    var onItemClick: (MenuItem) -> Unit
) : RecyclerView.Adapter<SideMenuAdapter.ViewHolder>() {

    private var items: List<MenuItem> = emptyList()

    fun submitList(newItems: List<MenuItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_side_menu, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.item_icon)
        private val title: TextView = itemView.findViewById(R.id.item_title)
        private val description: TextView = itemView.findViewById(R.id.item_description)

        init {
            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onItemClick(items[adapterPosition])
                }
            }
        }

        fun bind(item: MenuItem) {
            title.text = item.title

            if (item.description != null) {
                description.text = item.description
                description.isVisible = true
            } else {
                description.isVisible = false
            }

            if (item.iconRes != null) {
                icon.setImageResource(item.iconRes)
                icon.isVisible = true
            } else {
                icon.isVisible = false
            }

            itemView.isSelected = item.isSelected
        }
    }
}
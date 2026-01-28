package top.rootu.dddplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.MenuItem

class SideMenuAdapter(
    var onItemClick: (MenuItem) -> Unit
) : ListAdapter<MenuItem, SideMenuAdapter.ViewHolder>(MenuItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_side_menu, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.item_icon)
        private val title: TextView = itemView.findViewById(R.id.item_title)
        private val description: TextView = itemView.findViewById(R.id.item_description)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(pos))
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

    class MenuItemDiffCallback : DiffUtil.ItemCallback<MenuItem>() {
        override fun areItemsTheSame(oldItem: MenuItem, newItem: MenuItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MenuItem, newItem: MenuItem): Boolean {
            return oldItem == newItem
        }
    }
}
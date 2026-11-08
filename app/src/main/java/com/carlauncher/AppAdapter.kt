package com.carlauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.carlauncher.databinding.ItemAppBinding

/**
 * Shows apps either as fixed-width items in the bottom bar (dock = true)
 * or as cells of the app grid (dock = false).
 */
class AppAdapter(
    private val dock: Boolean,
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()

    fun submit(list: List<AppInfo>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val density = parent.resources.displayMetrics.density
        binding.root.layoutParams = if (dock) {
            RecyclerView.LayoutParams((88 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.b.icon.setImageDrawable(app.icon)
        holder.b.label.text = app.label
        holder.b.root.setOnClickListener { onClick(app) }
        holder.b.root.setOnLongClickListener {
            onLongClick(app)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}

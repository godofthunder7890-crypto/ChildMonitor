package com.system.service

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.system.service.R

class NotificationAdapter(
    private val items: MutableList<NotificationItem> = mutableListOf()
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvApp:   TextView = view.findViewById(R.id.tvApp)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvText:  TextView = view.findViewById(R.id.tvText)
        val tvTime:  TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvApp.text   = item.app
        holder.tvTitle.text = item.title
        holder.tvText.text  = item.text
        holder.tvTime.text  = item.time
    }

    override fun getItemCount() = items.size

    fun addNotification(item: NotificationItem) {
        items.add(0, item)
        if (items.size > 100) items.removeAt(items.size - 1)
        notifyItemInserted(0)
    }

    fun clear() { items.clear(); notifyDataSetChanged() }
}

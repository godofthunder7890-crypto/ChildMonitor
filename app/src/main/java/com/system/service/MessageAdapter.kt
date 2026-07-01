package com.system.service

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.system.service.R
import org.json.JSONObject

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MsgVH>() {

    private val items = mutableListOf<JSONObject>()

    fun add(msg: JSONObject) {
        items.add(msg); notifyItemInserted(items.size - 1)
    }

    fun setAll(list: List<JSONObject>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun clear() { items.clear(); notifyDataSetChanged() }

    class MsgVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvMsgText)
        val tvSugg: TextView = view.findViewById(R.id.tvMsgSuggested)
        val tvTime: TextView = view.findViewById(R.id.tvMsgTime)
    }

    override fun getItemViewType(pos: Int): Int {
        val sender    = items[pos].optString("sender", "child")
        val isDistress = items[pos].optBoolean("isDistress", false)
        return when {
            isDistress       -> 2
            sender == "parent" -> 1
            else              -> 0
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgVH {
        val layout = when (viewType) {
            1    -> R.layout.item_message_parent
            2    -> R.layout.item_message_distress
            else -> R.layout.item_message_child
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MsgVH(view)
    }

    override fun onBindViewHolder(holder: MsgVH, pos: Int) {
        val msg = items[pos]
        holder.tvText.text = msg.optString("text", "")
        val sugg = msg.optString("suggestedReply", "")
        if (sugg.isNotEmpty()) {
            holder.tvSugg.text       = "💡 Reply: $sugg"
            holder.tvSugg.visibility = View.VISIBLE
        } else {
            holder.tvSugg.visibility = View.GONE
        }
        val ts = msg.optLong("ts", 0)
        holder.tvTime.text = if (ts > 0)
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
        else ""
        holder.itemView.translationY = 60f; holder.itemView.alpha = 0f
        holder.itemView.animate().translationY(0f).alpha(1f).setDuration(300).start()
    }

    override fun getItemCount() = items.size
}

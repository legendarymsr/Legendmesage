package org.legend.legendmessage.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.legend.legendmessage.R
import org.legend.legendmessage.data.Message

class MessageAdapter(private var items: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.VH>() {

    fun submit(newItems: List<Message>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].outgoing) TYPE_OUT else TYPE_IN

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: TextView = view.findViewById(R.id.bubble)
        val meta: TextView = view.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_OUT) R.layout.item_message_out else R.layout.item_message_in
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        holder.bubble.text = msg.body
        val time = DateFormat.format("HH:mm", msg.timestamp)
        holder.meta.text = if (msg.outgoing && msg.pending) {
            holder.itemView.context.getString(R.string.chat_sending, time)
        } else {
            time
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private const val TYPE_IN = 0
        private const val TYPE_OUT = 1
    }
}

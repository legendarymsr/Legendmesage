package org.legend.legendmessage.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.legend.legendmessage.data.Contact
import org.legend.legendmessage.databinding.ItemContactBinding

class ContactAdapter(
    private var items: List<Contact>,
    private val onClick: (Contact) -> Unit,
) : RecyclerView.Adapter<ContactAdapter.VH>() {

    fun submit(newItems: List<Contact>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val contact = items[position]
        holder.binding.contactName.text = contact.displayName
        holder.binding.contactSub.text =
            if (contact.onionAddress.isBlank()) {
                holder.binding.root.context.getString(org.legend.legendmessage.R.string.contact_no_route)
            } else {
                contact.onionAddress.take(16) + "…"
            }
        holder.binding.root.setOnClickListener { onClick(contact) }
    }

    override fun getItemCount(): Int = items.size
}

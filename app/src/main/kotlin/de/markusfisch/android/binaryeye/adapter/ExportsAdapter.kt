package de.markusfisch.android.binaryeye.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.io.ArchiveStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportsAdapter(
	private val onOpen: (ArchiveStorage.Entry) -> Unit,
	private val onRename: (ArchiveStorage.Entry) -> Unit
) : RecyclerView.Adapter<ExportsAdapter.ViewHolder>() {

	private val items = mutableListOf<ArchiveStorage.Entry>()
	private val selections = mutableSetOf<String>()
	private var selectionMode = false
	private val dateFormat = SimpleDateFormat(
		"yyyy-MM-dd HH:mm:ss",
		Locale.getDefault()
	)

	fun submit(list: List<ArchiveStorage.Entry>) {
		items.clear()
		items.addAll(list)
		notifyDataSetChanged()
	}

	fun itemAt(position: Int): ArchiveStorage.Entry = items[position]

	fun removeAt(position: Int): ArchiveStorage.Entry {
		val entry = items.removeAt(position)
		notifyItemRemoved(position)
		return entry
	}

	fun setSelectionMode(enabled: Boolean) {
		if (selectionMode == enabled) {
			return
		}
		selectionMode = enabled
		if (!enabled) {
			selections.clear()
		}
		notifyDataSetChanged()
	}

	fun isSelectionMode() = selectionMode

	// Toggles the selection of the entry at the given position. Returns true
	// when the entry ended up selected.
	fun toggleSelection(position: Int): Boolean {
		val entry = items[position]
		return if (entry.name in selections) {
			selections.remove(entry.name)
			false
		} else {
			selections.add(entry.name)
			true
		}
	}

	fun selectAll() {
		selections.clear()
		items.forEach { selections.add(it.name) }
		notifyDataSetChanged()
	}

	fun clearSelection() {
		selections.clear()
	}

	fun getSelectedEntries(): List<ArchiveStorage.Entry> =
		items.filter { it.name in selections }

	fun getSelectedCount() = selections.size

	fun isSelected(position: Int): Boolean =
		items[position].name in selections

	override fun getItemCount() = items.size

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int
	): ViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(
			R.layout.row_export,
			parent,
			false
		)
		return ViewHolder(view)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val entry = items[position]
		holder.foreground.translationX = 0f
		holder.nameView.text = entry.name
		holder.timeView.text = dateFormat.format(Date(entry.lastModified))
		holder.foreground.isActivated = selectionMode && entry.name in selections
		if (selectionMode) {
			holder.itemView.setOnClickListener {
				toggleSelection(position)
				notifyItemChanged(position)
			}
			// Long-press rename is disabled while in selection mode.
			holder.itemView.setOnLongClickListener { true }
		} else {
			holder.itemView.setOnClickListener {
				onOpen(entry)
			}
			holder.itemView.setOnLongClickListener {
				onRename(entry)
				true
			}
		}
	}

	class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val foreground: View = view.findViewById(R.id.export_foreground)
		val nameView: TextView = view.findViewById(R.id.export_name)
		val timeView: TextView = view.findViewById(R.id.export_time)
	}
}

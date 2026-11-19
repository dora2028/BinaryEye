package de.markusfisch.android.binaryeye.adapter

import android.content.Context
import android.database.Cursor
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.app.prefs
import de.markusfisch.android.binaryeye.content.DiCode
import de.markusfisch.android.binaryeye.content.Gs1Medication
import de.markusfisch.android.binaryeye.content.Gs1MedicationParser
import de.markusfisch.android.binaryeye.content.ParsedContentType
import de.markusfisch.android.binaryeye.content.counts
import de.markusfisch.android.binaryeye.content.diCodeLine
import de.markusfisch.android.binaryeye.content.getContentPreview
import de.markusfisch.android.binaryeye.content.parsedContentTypeFromName
import de.markusfisch.android.binaryeye.database.Database
import de.markusfisch.android.binaryeye.database.ScanGroup
import java.text.Collator
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class ScansAdapter(
	private val context: Context,
	cursor: Cursor?,
	groups: List<ScanGroup>,
	private val sortOrder: String,
	private val limitGroups: Boolean,
	private val expandedGroups: MutableSet<String>,
	private val onPinClick: (Long, Boolean) -> Unit
) : BaseAdapter() {

	private val inflater = LayoutInflater.from(context)
	private val collator = Collator.getInstance(Locale.getDefault())
	private val groupById = groups.associateBy { it.id }

	private val scans = mutableListOf<ScanItem>()
	private var diCodeCounts: Map<Long, Int> = emptyMap()
	private val selections = mutableSetOf<Long>()
	private val groupSelections = mutableSetOf<String>()
	private var rows = emptyList<Row>()

	init {
		changeCursor(cursor)
	}

	fun changeCursor(cursor: Cursor?) {
		scans.clear()
		cursor?.use { c ->
			val idIndex = c.getColumnIndex(Database.SCANS_ID)
			val pinnedIndex = c.getColumnIndex(Database.SCANS_PINNED)
			val timeIndex = c.getColumnIndex(Database.SCANS_DATETIME)
			val nameIndex = c.getColumnIndex(Database.SCANS_NAME)
			val textIndex = c.getColumnIndex(Database.SCANS_TEXT)
			val rawIndex = c.getColumnIndex(Database.SCANS_RAW)
			val contentTypeIndex = c.getColumnIndex(
				Database.SCANS_CONTENT_TYPE
			)
			val formatIndex = c.getColumnIndex(Database.SCANS_FORMAT)
			val groupIndex = c.getColumnIndex(Database.SCANS_GROUP_ID)
			while (c.moveToNext()) {
				val text = c.getString(textIndex) ?: ""
				val raw = c.getBlob(rawIndex)
				val format = c.getString(formatIndex) ?: ""
				val storedType = parsedContentTypeFromName(
					c.getString(contentTypeIndex)
				)
				// Parsed once while reading the cursor instead of on every
				// bind so scrolling stays cheap. Rows that were stored before
				// GS1 support existed are UNKNOWN and get another chance here
				// instead of staying unparsed forever.
				val medication = if (
					storedType == ParsedContentType.UNKNOWN ||
					storedType == ParsedContentType.GS1_DATA
				) {
					Gs1MedicationParser.parse(
						text,
						raw ?: ByteArray(0),
						format
					)
				} else {
					null
				}
				scans.add(
					ScanItem(
						c.getLong(idIndex),
						c.getInt(pinnedIndex) != 0,
						c.getString(timeIndex) ?: "",
						c.getString(nameIndex),
						text,
						raw,
						if (medication != null) {
							ParsedContentType.GS1_DATA
						} else {
							storedType
						},
						format,
						groupIndex.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
						medication
					)
				)
			}
		}
		diCodeCounts = if (prefs.diCodeCounting) {
			DiCode.counts(
				scans.filter { it.groupId <= 0L }
					.sortedBy { it.id }
					.map { it.id to it.text }
			).associate { it.id to it.count }
		} else {
			emptyMap()
		}
		rebuildRows()
		notifyDataSetChanged()
	}

	// Exposes the number of scans (excluding group headers) so callers
	// can still tell whether the history is empty.
	fun getScanCount() = scans.size

	fun isHeader(position: Int): Boolean = rows.getOrNull(position) is Row.Header

	fun headerKeyAt(position: Int): String? =
		(rows.getOrNull(position) as? Row.Header)?.key

	fun isArchiveHeader(position: Int): Boolean =
		headerKeyAt(position) == ARCHIVE_KEY

	fun isHeaderSelected(position: Int): Boolean {
		val key = headerKeyAt(position) ?: return false
		if (key == ARCHIVE_KEY) {
			return false
		}
		return key in groupSelections
	}

	// Toggles the selection of a group header. Returns true when the group
	// ended up selected.
	fun toggleGroupSelection(position: Int): Boolean {
		val key = headerKeyAt(position) ?: return false
		// The archive header holds groups, not scans.
		if (key == ARCHIVE_KEY) {
			return false
		}
		return if (key in groupSelections) {
			groupSelections.remove(key)
			false
		} else {
			groupSelections.add(key)
			true
		}
	}

	// Selects every visible group header (pinned, default and every custom
	// group that currently has a header row).
	fun selectAllGroups() {
		groupSelections.clear()
		rows.forEach { row ->
			if (row is Row.Header && row.key != ARCHIVE_KEY) {
				groupSelections.add(row.key)
			}
		}
		notifyDataSetChanged()
	}

	fun clearGroupSelection() {
		groupSelections.clear()
	}

	fun getSelectedGroupKeys(): Set<String> = groupSelections.toSet()

	fun getSelectedGroupCount(): Int = groupSelections.size

	// Maps a set of group keys back to the ids of the scans that belong to
	// them. Used to export the text of one or more selected groups.
	fun getScanIdsForGroupKeys(keys: Set<String>): Set<Long> =
		scans.filter { groupKeyOf(it) in keys }.map { it.id }.toSet()

	fun toggleGroup(position: Int) {
		val header = rows.getOrNull(position) as? Row.Header ?: return
		if (header.key in expandedGroups) {
			expandedGroups.remove(header.key)
			// Collapsed groups must not take part in selection, so drop any
			// items that were selected while the group was still expanded.
			if (header.key == ARCHIVE_KEY) {
				dropArchivedSelections()
			} else {
				removeSelections(header.key)
			}
		} else {
			expandedGroups.add(header.key)
		}
		rebuildRows()
		notifyDataSetChanged()
	}

	// Expands every group (pinned, the default/ungrouped group and every
	// custom group), so all scanned items become visible.
	fun expandAll() {
		expandedGroups.add(PINNED_GROUP_KEY)
		expandedGroups.add(DEFAULT_GROUP_KEY)
		expandedGroups.add(ARCHIVE_KEY)
		groupById.keys.forEach { expandedGroups.add("group:$it") }
		rebuildRows()
		notifyDataSetChanged()
	}

	// Collapses every group so only the group headers remain.
	fun collapseAll() {
		expandedGroups.clear()
		rebuildRows()
		notifyDataSetChanged()
	}

	fun isAllExpanded(): Boolean =
		PINNED_GROUP_KEY in expandedGroups &&
			DEFAULT_GROUP_KEY in expandedGroups &&
			(ARCHIVE_KEY in expandedGroups || archivedGroupCount() == 0) &&
			groupById.keys.all { "group:$it" in expandedGroups }

	fun select(view: View, id: Long, position: Int) {
		if (isHeader(position)) {
			return
		}
		if (id in selections) {
			selections.remove(id)
			view.select(false)
		} else {
			selections.add(id)
			view.select(true)
		}
	}

	// Selects every *visible* item only. Collapsed groups are not part of
	// `rows`, so their items are intentionally left out.
	fun selectAll() {
		selections.clear()
		rows.forEach { row ->
			if (row is Row.Scan) {
				selections.add(row.item.id)
			}
		}
		notifyDataSetChanged()
	}

	// Inverts the selection of every *visible* item only. Items inside
	// collapsed groups are not present in `rows`, so they are left as-is.
	fun invertSelection() {
		rows.forEach { row ->
			if (row is Row.Scan) {
				val id = row.item.id
				if (id in selections) {
					selections.remove(id)
				} else {
					selections.add(id)
				}
			}
		}
		notifyDataSetChanged()
	}

	fun clearSelection() {
		selections.clear()
	}

	fun forSelection(callback: (Long, Int) -> Unit) {
		selections.forEach { id ->
			val position = indexOfScan(id)
			if (position >= 0) {
				callback(id, position)
			}
		}
	}

	fun getSelectedIds(): List<Long> = selections.toList()

	fun getSelectedContent(separator: String): String {
		val sb = StringBuilder()
		selections.forEach { id ->
			scanById(id)?.let { item ->
				if (sb.isNotEmpty()) {
					sb.append(separator)
				}
				sb.append(item.text)
			}
		}
		return sb.toString()
	}

	fun getName(position: Int) = scanAt(position)?.name

	fun getContent(position: Int) = scanAt(position)?.text

	fun getNameById(id: Long) = scanById(id)?.name

	fun getContentById(id: Long) = scanById(id)?.text

	private fun rebuildRows() {
		val newRows = mutableListOf<Row>()
		val pinnedItems = scans.filter { it.pinned }
		if (pinnedItems.isNotEmpty()) {
			val key = PINNED_GROUP_KEY
			newRows.add(
				Row.Header(
					key,
					context.getString(R.string.pinned_group),
					pinnedItems.size
				)
			)
			if (key in expandedGroups) {
				pinnedItems.sortedByDescending { it.dateTime }
					.forEach { newRows.add(Row.Scan(it)) }
			}
		}
		val grouped = groupedScans()
		val custom = grouped.filter { it.first > 0L }
		val visible = if (limitGroups) custom.take(GROUP_LIMIT) else custom
		val archived = if (limitGroups) custom.drop(GROUP_LIMIT) else emptyList()
		grouped.filter { it.first <= 0L }.forEach {
			addGroupRows(newRows, it)
		}
		visible.forEach { addGroupRows(newRows, it) }
		if (archived.isNotEmpty()) {
			// Everything beyond the limit ends up in a single archive
			// header at the very bottom.
			newRows.add(
				Row.Header(
					ARCHIVE_KEY,
					context.getString(R.string.archive_group),
					archived.size
				)
			)
			if (ARCHIVE_KEY in expandedGroups) {
				archived.forEach { addGroupRows(newRows, it) }
			}
		}
		rows = newRows
	}

	private fun addGroupRows(
		rows: MutableList<Row>,
		entry: Pair<Long, List<ScanItem>>
	) {
		val (groupId, items) = entry
		val key = "group:$groupId"
		rows.add(Row.Header(key, groupTitle(groupId), items.size))
		if (key in expandedGroups) {
			items.sortedByDescending { it.dateTime }
				.forEach { rows.add(Row.Scan(it)) }
		}
	}

	private fun groupedScans(): List<Pair<Long, List<ScanItem>>> =
		scans.filter { !it.pinned }
			.groupBy { it.groupId.takeIf { g -> g > 0L } ?: 0L }
			.toList()
			.sortedWith(groupComparator())

	// Number of custom groups that don't fit into the visible range.
	private fun archivedGroupCount(): Int {
		if (!limitGroups) {
			return 0
		}
		val custom = groupedScans().count { it.first > 0L }
		return (custom - GROUP_LIMIT).coerceAtLeast(0)
	}

	private fun dropArchivedSelections() {
		val archivedIds = groupedScans()
			.filter { it.first > 0L }
			.drop(GROUP_LIMIT)
			.map { it.first }
			.toSet()
		if (archivedIds.isEmpty()) {
			return
		}
		scans.forEach { item ->
			if (item.groupId in archivedIds) {
				selections.remove(item.id)
			}
		}
	}

	private fun groupComparator() =
		Comparator<Pair<Long, List<ScanItem>>> { (aId, _), (bId, _) ->
			val aDefault = aId <= 0L
			val bDefault = bId <= 0L
			when {
				aDefault && bDefault -> 0
				// The ungrouped "default" group is always pinned to the top.
				aDefault -> -1
				bDefault -> 1
				else -> {
					val a = groupById[aId]
					val b = groupById[bId]
					val aPinned = a?.pinned == true
					val bPinned = b?.pinned == true
					when {
						aPinned && bPinned -> compareBySortOrder(a, b)
						aPinned -> -1
						bPinned -> 1
						else -> compareBySortOrder(a, b)
					}
				}
			}
		}

	private fun compareBySortOrder(a: ScanGroup?, b: ScanGroup?): Int =
		when (sortOrder) {
			"time_asc" -> (a?.created ?: 0L).compareTo(b?.created ?: 0L)
			"time_desc" -> (b?.created ?: 0L).compareTo(a?.created ?: 0L)
			"name_desc" -> collator.compare(b?.name ?: "", a?.name ?: "")
			else -> collator.compare(a?.name ?: "", b?.name ?: "")
		}

	private fun groupTitle(groupId: Long): String =
		if (groupId <= 0L) {
			context.getString(R.string.group_other)
		} else {
			groupById[groupId]?.name ?: context.getString(R.string.group_other)
		}

	// Removes the selection of every item that belongs to the given group
	// key. Used when a group is collapsed so that collapsed items never stay
	// selected.
	private fun removeSelections(key: String) {
		scans.forEach { item ->
			if (groupKeyOf(item) == key) {
				selections.remove(item.id)
			}
		}
	}

	// Same key derivation as rebuildRows(): pinned items belong to the pinned
	// group, ungrouped items to the default group, everything else to its
	// custom group.
	private fun groupKeyOf(item: ScanItem): String = when {
		item.pinned -> PINNED_GROUP_KEY
		item.groupId > 0L -> "group:${item.groupId}"
		else -> DEFAULT_GROUP_KEY
	}

	private fun scanById(id: Long) = scans.firstOrNull { it.id == id }

	private fun scanAt(position: Int) =
		(rows.getOrNull(position) as? Row.Scan)?.item

	private fun indexOfScan(id: Long) = rows.indexOfFirst {
		(it as? Row.Scan)?.item?.id == id
	}

	override fun getViewTypeCount() = 2

	override fun getItemViewType(position: Int) = when (rows[position]) {
		is Row.Header -> VIEW_TYPE_HEADER
		is Row.Scan -> VIEW_TYPE_SCAN
	}

	override fun getCount() = rows.size

	override fun getItem(position: Int): Any = rows[position]

	override fun getItemId(position: Int) = when (val row = rows[position]) {
		is Row.Scan -> row.item.id
		is Row.Header -> NO_ID
	}

	override fun getView(
		position: Int,
		convertView: View?,
		parent: ViewGroup
	): View = when (val row = rows[position]) {
		is Row.Header -> getHeaderView(row, convertView, parent)
		is Row.Scan -> getScanView(row.item, convertView, parent)
	}

	private fun getHeaderView(
		header: Row.Header,
		convertView: View?,
		parent: ViewGroup
	): View {
		val holder: HeaderViewHolder
		val view: View
		val oldHolder = convertView?.tag as? HeaderViewHolder
		if (oldHolder != null) {
			view = convertView
			holder = oldHolder
		} else {
			view = inflater.inflate(R.layout.row_scan_group, parent, false)
			holder = HeaderViewHolder(
				view.findViewById(R.id.group_expand),
				view.findViewById(R.id.group_title),
				view.findViewById(R.id.group_count)
			)
			view.tag = holder
		}
		val expanded = header.key in expandedGroups
		holder.titleView.text = header.title
		holder.countView.text = header.count.toString()
		holder.expandView.rotation = if (expanded) 0f else -90f
		holder.expandView.contentDescription = context.getString(
			if (expanded) R.string.collapse_group else R.string.expand_group
		)
		// Highlight the header while it is selected in group multi-select.
		val selected = header.key in groupSelections
		if (view.isActivated != selected) {
			view.isActivated = selected
			view.refreshDrawableState()
		}
		return view
	}

	private fun getScanView(
		item: ScanItem,
		convertView: View?,
		parent: ViewGroup
	): View {
		val holder: ScanViewHolder
		val view: View
		val oldHolder = convertView?.tag as? ScanViewHolder
		if (oldHolder != null) {
			view = convertView
			holder = oldHolder
		} else {
			view = inflater.inflate(R.layout.row_scan, parent, false)
			holder = ScanViewHolder(
				view.findViewById(R.id.format_icon),
				view.findViewById(R.id.meta),
				view.findViewById(R.id.format),
				view.findViewById(R.id.di_code),
				view.findViewById(R.id.group_badge),
				view.findViewById(R.id.content),
				view.findViewById(R.id.pin)
			)
			view.tag = holder
		}
		holder.metaView.text = formatDateTime(item.dateTime)
		holder.formatView.text = item.format.prettifyFormatName()
			.withContentType(context, item.contentType)
		holder.formatIconView.setImageResource(item.format.toBarcodeIcon())
		val diCode = if (prefs.diCodeDetection) {
			DiCode.of(item.text)
		} else {
			null
		}
		if (diCode == null) {
			holder.diCodeView.visibility = View.GONE
		} else {
			holder.diCodeView.text = context.diCodeLine(
				diCode,
				diCodeCounts[item.id]
			)
			holder.diCodeView.visibility = View.VISIBLE
		}
		val group = item.groupId.takeIf { it > 0L }?.let { groupById[it] }
		if (group != null) {
			holder.groupView.text = SpannableString("● ${group.name}").apply {
				setSpan(
					ForegroundColorSpan(group.color),
					0,
					1,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
				)
			}
			holder.groupView.visibility = View.VISIBLE
		} else {
			holder.groupView.visibility = View.GONE
		}
		var icon = 0
		holder.contentView.text = when {
			item.name?.isNotEmpty() == true -> {
				icon = R.drawable.ic_label
				item.name
			}

			item.medication != null -> item.medication.gtin

			item.text.isEmpty() ||
				item.contentType == ParsedContentType.GS1_DATA ->
				getContentPreview(
					context,
					item.text,
					item.raw ?: ByteArray(0),
					item.format
				)

			else -> item.text
		}
		holder.contentView.setCompoundDrawablesWithIntrinsicBounds(
			icon, 0, 0, 0
		)
		holder.pinView.apply {
			setImageResource(
				if (item.pinned) {
					R.drawable.ic_action_pin
				} else {
					R.drawable.ic_action_pin_outline
				}
			)
			contentDescription = context.getString(
				if (item.pinned) {
					R.string.unpin_item
				} else {
					R.string.pin_item
				}
			)
			setOnClickListener {
				onPinClick(item.id, !item.pinned)
			}
		}
		val selected = item.id in selections
		view.post {
			// Needs to be put on the queue to work.
			view.select(selected)
		}
		return view
	}

	private fun View.select(selected: Boolean) {
		isActivated = selected
		refreshDrawableState()
	}

	private data class ScanItem(
		val id: Long,
		val pinned: Boolean,
		val dateTime: String,
		val name: String?,
		val text: String,
		val raw: ByteArray?,
		val contentType: ParsedContentType,
		val format: String,
		val groupId: Long,
		val medication: Gs1Medication? = null
	)

	private sealed class Row {
		data class Header(
			val key: String,
			val title: String,
			val count: Int
		) : Row()

		data class Scan(
			val item: ScanItem
		) : Row()
	}

	private data class HeaderViewHolder(
		val expandView: ImageView,
		val titleView: TextView,
		val countView: TextView
	)

	private data class ScanViewHolder(
		val formatIconView: ImageView,
		val metaView: TextView,
		val formatView: TextView,
		val diCodeView: TextView,
		val groupView: TextView,
		val contentView: TextView,
		val pinView: ImageButton
	)

	companion object {
		const val VIEW_TYPE_HEADER = 0
		const val VIEW_TYPE_SCAN = 1
		const val NO_ID = -1L
		const val PINNED_GROUP_KEY = "pinned"
		const val ARCHIVE_KEY = "archive"
		const val GROUP_LIMIT = 20
		const val DEFAULT_GROUP_KEY = "group:0"
	}
}

private val formatWordBoundary =
	"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])".toRegex()

fun Context.setupFormatSpinner(spinner: Spinner): List<String> {
	val names = resources.getStringArray(
		R.array.barcode_formats_names
	).toMutableList().apply {
		add(0, getString(R.string.all_formats))
	}
	val values = resources.getStringArray(
		R.array.barcode_formats_values
	).toMutableList().apply {
		add(0, "")
	}
	spinner.adapter = ArrayAdapter(
		this,
		android.R.layout.simple_spinner_item,
		names
	).apply {
		setDropDownViewResource(
			android.R.layout.simple_spinner_dropdown_item
		)
	}
	return values
}

fun String.prettifyFormatName() = replace("_", " ")
	.replace(formatWordBoundary, " ")

private fun String.withContentType(
	context: Context,
	type: ParsedContentType
) = if (type == ParsedContentType.UNKNOWN) {
	this
} else {
	context.getString(
		R.string.scan_meta_data,
		this,
		context.getString(type.resId)
	)
}

fun String.toFormatDescriptionResId(): Int = when (this) {
	"Aztec" -> R.string.format_description_aztec
	"Codabar" -> R.string.format_description_codabar
	"Code39",
	"Code39Std" -> R.string.format_description_code39

	"Code39Ext" -> R.string.format_description_code39ext
	"Code32" -> R.string.format_description_code32
	"PZN" -> R.string.format_description_pzn
	"Code93" -> R.string.format_description_code93
	"Code128" -> R.string.format_description_code128
	"DataBar" -> R.string.format_description_databar
	"DataBarOmni" -> R.string.format_description_databar_omni
	"DataBarStk" -> R.string.format_description_databar_stk
	"DataBarLtd" -> R.string.format_description_databar_ltd
	"DataBarExp" -> R.string.format_description_databar_exp
	"DataBarExpStk" -> R.string.format_description_databar_exp_stk
	"DataMatrix" -> R.string.format_description_data_matrix
	"DXFilmEdge" -> R.string.format_description_dx_film_edge
	"EAN8" -> R.string.format_description_ean8
	"EAN13" -> R.string.format_description_ean13
	"ITF" -> R.string.format_description_itf
	"MaxiCode" -> R.string.format_description_maxicode
	"PDF417" -> R.string.format_description_pdf417
	"Telepen" -> R.string.format_description_telepen
	"TelepenAlpha" -> R.string.format_description_telepen_alpha
	"TelepenNumeric" -> R.string.format_description_telepen_numeric
	"QRCode" -> R.string.format_description_qr_code
	"MicroQRCode" -> R.string.format_description_micro_qr_code
	"RMQRCode" -> R.string.format_description_rmqr_code
	"UPCA" -> R.string.format_description_upca
	"UPCE" -> R.string.format_description_upce
	else -> 0
}

private fun String?.toBarcodeIcon(): Int = when (this) {
	"QRCode",
	"RMQRCode" -> R.drawable.ic_barcode_qr_code

	"MicroQRCode" -> R.drawable.ic_barcode_micro_qr

	"Aztec" -> R.drawable.ic_barcode_aztec
	"DataMatrix",
	"MaxiCode" -> R.drawable.ic_barcode_data_matrix

	"PDF417" -> R.drawable.ic_barcode_pdf417

	else -> R.drawable.ic_barcode_linear
}

private fun formatDateTime(rfc: String): String {
	try {
		SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(rfc)?.let {
			return DateFormat.getDateTimeInstance(
				DateFormat.LONG,
				DateFormat.SHORT
			).format(it)
		}
	} catch (_: ParseException) {
		// Ignore and fall through.
	}
	return rfc
}

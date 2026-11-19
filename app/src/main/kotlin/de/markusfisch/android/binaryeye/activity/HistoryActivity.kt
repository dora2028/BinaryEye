package de.markusfisch.android.binaryeye.activity

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.adapter.ExportsAdapter
import de.markusfisch.android.binaryeye.adapter.ScansAdapter
import de.markusfisch.android.binaryeye.app.alertDialog
import de.markusfisch.android.binaryeye.app.db
import de.markusfisch.android.binaryeye.app.hasWritePermission
import de.markusfisch.android.binaryeye.app.prefs
import de.markusfisch.android.binaryeye.content.DiCode
import de.markusfisch.android.binaryeye.content.copyToClipboard
import de.markusfisch.android.binaryeye.content.shareAsFile
import de.markusfisch.android.binaryeye.content.shareText
import de.markusfisch.android.binaryeye.content.wipeLastShareFile
import de.markusfisch.android.binaryeye.database.Database
import de.markusfisch.android.binaryeye.database.GROUP_COLORS
import de.markusfisch.android.binaryeye.database.ScanGroup
import de.markusfisch.android.binaryeye.database.exportCsv
import de.markusfisch.android.binaryeye.database.exportDatabase
import de.markusfisch.android.binaryeye.database.exportJson
import de.markusfisch.android.binaryeye.io.ArchiveStorage
import de.markusfisch.android.binaryeye.io.askForFileName
import de.markusfisch.android.binaryeye.io.toSaveResult
import de.markusfisch.android.binaryeye.io.writeExternalFileTo
import de.markusfisch.android.binaryeye.view.lockStatusBarColor
import de.markusfisch.android.binaryeye.view.setPaddingFromWindowInsets
import de.markusfisch.android.binaryeye.view.systemBarListViewScrollListener
import de.markusfisch.android.binaryeye.view.unlockStatusBarColor
import de.markusfisch.android.binaryeye.view.useVisibility
import de.markusfisch.android.binaryeye.widget.toast
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : AbstractBaseActivity() {
	private lateinit var useHistorySwitch: SwitchCompat
	private lateinit var listView: ListView
	private lateinit var fab: View
	private lateinit var progressView: View

	private val parentJob = Job()
	private val scope = CoroutineScope(Dispatchers.IO + parentJob)
	private val actionModeCallback = object : ActionMode.Callback {
		override fun onCreateActionMode(
			mode: ActionMode,
			menu: Menu
		): Boolean {
			mode.menuInflater.inflate(
				R.menu.fragment_history_edit,
				menu
			)
			// SDK35+ doesn't draw a status bar background anymore.
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
				lockStatusBarColor()
				val ac = this@HistoryActivity
				@Suppress("DEPRECATION")
				ac.window.statusBarColor = MaterialColors.getColor(
					ac,
					com.google.android.material.R.attr.colorSecondaryContainer,
					Color.TRANSPARENT
				)
			}
			return true
		}

		override fun onPrepareActionMode(
			mode: ActionMode,
			menu: Menu
		): Boolean {
			return false
		}

		override fun onActionItemClicked(
			mode: ActionMode,
			item: MenuItem
		): Boolean {
			val ac = this@HistoryActivity
			return when (item.itemId) {
				R.id.select_all -> {
					scansAdapter?.selectAll()
					true
				}

				R.id.invert_selection -> {
					scansAdapter?.invertSelection()
					true
				}

				R.id.copy_scan -> {
					scansAdapter?.getSelectedContent("\n")?.let {
						ac.copyToClipboard(it)
						ac.toast(R.string.copied_to_clipboard)
					}
					closeActionMode()
					true
				}

				R.id.edit_label -> {
					scansAdapter?.forSelection { id, _ ->
						ac.askForName(
							id,
							scansAdapter?.getNameById(id),
							scansAdapter?.getContentById(id)
						)
					}
					closeActionMode()
					true
				}

				R.id.assign_group -> {
					ac.assignToGroup()
					true
				}

				R.id.add_remark -> {
					ac.askToAddDiCodeRemark(
						scansAdapter?.getSelectedIds().orEmpty()
					)
					true
				}

				R.id.export_selected -> {
					ac.exportSelectedAsTextArchive()
					true
				}

				R.id.remove_scan -> {
					scansAdapter?.getSelectedIds()?.let {
						if (it.isNotEmpty()) {
							ac.askToRemoveScans(it)
						}
					}
					closeActionMode()
					true
				}

				else -> false
			}
		}

		override fun onDestroyActionMode(mode: ActionMode) {
			closeActionMode()
		}
	}

	private val groupActionModeCallback = object : ActionMode.Callback {
		override fun onCreateActionMode(
			mode: ActionMode,
			menu: Menu
		): Boolean {
			mode.menuInflater.inflate(
				R.menu.fragment_history_group_edit,
				menu
			)
			mode.title = getString(
				R.string.selected_count,
				scansAdapter?.getSelectedGroupCount() ?: 0
			)
			// SDK35+ doesn't draw a status bar background anymore.
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
				lockStatusBarColor()
				val ac = this@HistoryActivity
				@Suppress("DEPRECATION")
				ac.window.statusBarColor = MaterialColors.getColor(
					ac,
					com.google.android.material.R.attr.colorSecondaryContainer,
					Color.TRANSPARENT
				)
			}
			return true
		}

		override fun onPrepareActionMode(
			mode: ActionMode,
			menu: Menu
		): Boolean {
			return false
		}

		override fun onActionItemClicked(
			mode: ActionMode,
			item: MenuItem
		): Boolean {
			return when (item.itemId) {
				R.id.select_all -> {
					scansAdapter?.selectAllGroups()
					updateGroupActionModeTitle()
					true
				}

				R.id.export_selected -> {
					exportSelectedGroupsAsTextArchive()
					true
				}

				else -> false
			}
		}

		override fun onDestroyActionMode(mode: ActionMode) {
			closeGroupActionMode()
		}
	}

	private var scansAdapter: ScansAdapter? = null
	private var listViewState: Parcelable? = null
	private var actionMode: ActionMode? = null
	private var groupSelectionMode = false
	private var groupActionMode: ActionMode? = null
	private var filter: String? = null
	private var groupFilter: Long? = null
	private var clearListMenuItem: MenuItem? = null
	private var exportHistoryMenuItem: MenuItem? = null
	private var shareAsFileMenuItem: MenuItem? = null
	private var expandCollapseMenuItem: MenuItem? = null
	private var exportsAdapter: ExportsAdapter? = null
	private var exportsHintView: TextView? = null
	private var exportsDirValueView: TextView? = null

	// Keys of the groups that are currently expanded. An empty set means
	// every group is collapsed (the default), which keeps the list cheap to
	// render when the history contains a huge number of scans.
	private val expandedGroups = mutableSetOf<String>()

	// Whether the history contains any scan at all (independent of the
	// current search/filter), used to keep the expand/collapse button
	// visible even when a search with collapsed groups returns nothing.
	private var hasHistory = false

	private val exportDirectoryPicker =
		registerForActivityResult(
			ActivityResultContracts.OpenDocumentTree()
		) { uri ->
			if (uri != null) {
				try {
					contentResolver.takePersistableUriPermission(
						uri,
						Intent.FLAG_GRANT_READ_URI_PERMISSION or
							Intent.FLAG_GRANT_WRITE_URI_PERMISSION
					)
				} catch (_: SecurityException) {
					// The grant may still work for the current session.
				}
				prefs.exportDirectoryUri = uri.toString()
				toast(R.string.directory_changed)
				refreshExports()
			}
		}

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setScreenContentView(R.layout.activity_history)
		setTitle(R.string.history)

		useHistorySwitch = findViewById(
			R.id.use_history
		)
		initHistorySwitch(useHistorySwitch)

		listView = findViewById(R.id.scans)
		listView.setOnItemClickListener { _, v, position, id ->
			if (scansAdapter?.isHeader(position) == true) {
				if (groupSelectionMode) {
					scansAdapter?.toggleGroupSelection(position)
					updateGroupActionModeTitle()
					scansAdapter?.notifyDataSetChanged()
				} else {
					scansAdapter?.toggleGroup(position)
					updateExpandCollapseMenuItem()
				}
			} else if (groupSelectionMode) {
				// In group multi-select, taps on scan rows are ignored.
			} else if (actionMode != null) {
				scansAdapter?.select(v, id, position)
			} else {
				showScan(id)
			}
		}
		listView.setOnItemLongClickListener { _, v, position, id ->
			if (scansAdapter?.isArchiveHeader(position) == true) {
				// The archive header only groups other groups.
				true
			} else if (scansAdapter?.isHeader(position) == true) {
				// Long-pressing a group header enters group multi-select and
				// selects that group.
				if (actionMode != null) {
					closeActionMode()
				}
				scansAdapter?.toggleGroupSelection(position)
				if (groupActionMode == null) {
					groupSelectionMode = true
					scansAdapter?.notifyDataSetChanged()
					groupActionMode = delegate.startSupportActionMode(
						groupActionModeCallback
					)
				} else {
					updateGroupActionModeTitle()
					scansAdapter?.notifyDataSetChanged()
				}
				true
			} else {
				if (groupSelectionMode) {
					// In group multi-select, long-pressing a scan is ignored.
					true
				} else {
					scansAdapter?.select(v, id, position)
					if (actionMode == null) {
						actionMode = delegate.startSupportActionMode(
							actionModeCallback
						)
					}
					true
				}
			}
		}
		listView.setOnScrollListener(systemBarListViewScrollListener)

		fab = findViewById(R.id.share)
		fab.setOnClickListener { v ->
			v.context.pickListSeparatorAndShare(false)
		}

		progressView = findViewById(R.id.progress_view)

		findViewById<View>(R.id.inset_layout).setPaddingFromWindowInsets(bottom = false)
		listView.setPaddingFromWindowInsets()
	}

	override fun onDestroy() {
		super.onDestroy()
		scansAdapter?.changeCursor(null)
		parentJob.cancel()
		wipeLastShareFile()
	}

	override fun onResume() {
		super.onResume()
		update()
	}

	override fun onPause() {
		super.onPause()
		listViewState = listView.onSaveInstanceState()
	}

	override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
		inflater.inflate(R.menu.fragment_history, menu)
		val searchMenuItem = menu.findItem(R.id.search)
		initSearchView(searchMenuItem)
		val visible = scansAdapter?.getScanCount() != 0
		searchMenuItem?.isVisible = visible
		exportHistoryMenuItem = menu.findItem(R.id.export_history)?.apply {
			isVisible = visible
		}
		shareAsFileMenuItem = menu.findItem(R.id.share_as_file)?.apply {
			isVisible = visible
		}
		clearListMenuItem = menu.findItem(R.id.clear)?.apply {
			isVisible = visible
		}
		expandCollapseMenuItem = menu.findItem(R.id.expand_collapse_all)
		updateExpandCollapseMenuItem()
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		updateExpandCollapseMenuItem()
		return super.onPrepareOptionsMenu(menu)
	}

	private fun updateExpandCollapseMenuItem() {
		val item = expandCollapseMenuItem ?: return
		val allExpanded = scansAdapter?.isAllExpanded() == true
		item.setIcon(
			if (allExpanded) {
				R.drawable.ic_action_unfold_less
			} else {
				R.drawable.ic_action_unfold_more
			}
		)
		item.setTitle(
			if (allExpanded) {
				R.string.collapse_all_groups
			} else {
				R.string.expand_all_groups
			}
		)
		item.isVisible = hasHistory
	}

	private fun toggleExpandAll() {
		val adapter = scansAdapter ?: return
		if (adapter.isAllExpanded()) {
			adapter.collapseAll()
		} else {
			adapter.expandAll()
		}
		updateExpandCollapseMenuItem()
		// When searching, the result set depends on which groups are
		// expanded, so re-run the query to reflect the new state.
		if (!filter.isNullOrEmpty()) {
			update(filter)
		}
	}

	private fun initSearchView(item: MenuItem?) {
		item ?: return
		val ac = this
		val searchView = item.actionView as SearchView
		val searchManager = ac.getSystemService(
			Context.SEARCH_SERVICE
		) as SearchManager
		searchView.setSearchableInfo(
			searchManager.getSearchableInfo(ac.componentName)
		)
		searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
			override fun onQueryTextSubmit(query: String): Boolean {
				update(query)
				return false
			}

			override fun onQueryTextChange(query: String): Boolean {
				update(query)
				return false
			}
		})
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.filter -> {
				askToFilter()
				true
			}

			R.id.manage_groups -> {
				askToManageGroups()
				true
			}

			R.id.expand_collapse_all -> {
				toggleExpandAll()
				true
			}

			R.id.export_txt -> {
				exportAsTextArchive()
				true
			}

			R.id.export_archive -> {
				askToShowExports()
				true
			}

			R.id.add_remark -> {
				askToAddDiCodeRemark()
				true
			}

			R.id.advanced_features -> {
				askToShowAdvancedFeatures()
				true
			}

			R.id.clear -> {
				this.askToRemoveScans()
				true
			}

			R.id.export_history -> {
				askToExportToFile()
				true
			}

			R.id.share_as_file -> {
				this.pickListSeparatorAndShare(true)
				true
			}

			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun initHistorySwitch(switchView: SwitchCompat) {
		switchView.setOnCheckedChangeListener { _, isChecked ->
			prefs.useHistory = isChecked
		}
		if (prefs.useHistory) {
			switchView.toggle()
		}
	}

	private fun updateAndClearFilter() {
		filter = null
		update()
	}

	private fun update(query: String? = null) {
		query?.let { filter = it }
		scope.launch {
			val groups = readGroups()
			val totalScans = db.countScans()
			// Collapsed groups are excluded from search so that searching
			// through a very large history stays fast.
			val searching = !filter.isNullOrEmpty()
			val cursor = if (searching) {
				db.getScans(
					filter,
					groupFilter,
					excludePinned = ScansAdapter.PINNED_GROUP_KEY !in expandedGroups,
					excludeDefault = ScansAdapter.DEFAULT_GROUP_KEY !in expandedGroups,
					excludeGroupIds = groups.map { it.id }
						.filter { "group:$it" !in expandedGroups }
						.toSet()
				)
			} else {
				db.getScans(filter, groupFilter)
			}
			withContext(Dispatchers.Main) {
				val ac = this@HistoryActivity
				hasHistory = totalScans > 0
				ac.title = resources.getQuantityString(
					R.plurals.scan_count,
					cursor.count,
					cursor.count
				)
				val hasScans = cursor.count > 0
				if (filter == null) {
					if (!hasScans) {
						listView.emptyView = useHistorySwitch
					}
					ac.invalidateOptionsMenu()
				}
				enableMenuItems(hasScans)
				fab.visibility = if (hasScans) {
					View.VISIBLE
				} else {
					View.GONE
				}
				cursor.let { cursor ->
					// Close previous cursor.
					scansAdapter?.also { it.changeCursor(null) }
					scansAdapter = ScansAdapter(
						ac,
						cursor,
						groups,
						prefs.groupSortOrder,
						prefs.limitGroups,
						expandedGroups
					) { id, pinned ->
						if (actionMode == null) {
							scope.launch {
								db.setPinned(id, pinned)
								withContext(Dispatchers.Main) {
									update()
								}
							}
						}
					}
					listView.adapter = scansAdapter
					listViewState?.also {
						listView.onRestoreInstanceState(it)
					}
					updateExpandCollapseMenuItem()
				}
			}
		}
	}

	private fun enableMenuItems(enabled: Boolean) {
		clearListMenuItem?.isEnabled = enabled
		exportHistoryMenuItem?.isEnabled = enabled
		shareAsFileMenuItem?.isEnabled = enabled
	}

	private fun closeActionMode() {
		unlockStatusBarColor()
		scansAdapter?.clearSelection()
		actionMode?.finish()
		actionMode = null
		scansAdapter?.notifyDataSetChanged()
	}

	private fun closeGroupActionMode() {
		unlockStatusBarColor()
		scansAdapter?.clearGroupSelection()
		groupSelectionMode = false
		groupActionMode?.finish()
		groupActionMode = null
		scansAdapter?.notifyDataSetChanged()
	}

	private fun updateGroupActionModeTitle() {
		groupActionMode?.title = getString(
			R.string.selected_count,
			scansAdapter?.getSelectedGroupCount() ?: 0
		)
	}

	private fun exportSelectedGroupsAsTextArchive() {
		val keys = scansAdapter?.getSelectedGroupKeys() ?: return
		if (keys.isEmpty()) {
			return
		}
		val ids = scansAdapter?.getScanIdsForGroupKeys(keys) ?: return
		if (ids.isEmpty()) {
			toast(R.string.error_saving_file)
			return
		}
		closeGroupActionMode()
		scope.launch {
			val text = db.getScansDetailed(ids.toLongArray()).use {
				it.exportText("\n")
			}
			if (text.isNotEmpty()) {
				writeTextArchive(text)
			}
		}
	}

	private fun showScan(id: Long) = db.getScan(id)?.also { scan ->
		closeActionMode()
		try {
			startActivity(DecodeActivity.newIntent(this, scan))
		} catch (_: IllegalArgumentException) {
			// Ignore, can never happen.
		}
	}

	// Dialogs don't have a parent layout.
	@SuppressLint("InflateParams")
	private fun Context.askForName(id: Long, text: String?, content: String?) {
		val view = LayoutInflater.from(this).inflate(
			R.layout.dialog_enter_name, null
		)
		val nameView = view.findViewById<EditText>(R.id.name)
		nameView.setText(text)
		MaterialAlertDialogBuilder(this)
			.setTitle(
				if (content.isNullOrEmpty()) {
					getString(R.string.binary_data)
				} else {
					content
				}
			)
			.setView(view)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val name = nameView.text.toString()
				db.renameScan(id, name)
				update()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.show()
	}

	private fun Context.askToRemoveScans(ids: List<Long>) {
		MaterialAlertDialogBuilder(this)
			.setMessage(
				if (ids.size > 1) {
					R.string.really_remove_selected_scans
				} else {
					R.string.really_remove_scan
				}
			)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				ids.forEach { db.removeScan(it) }
				if (scansAdapter?.getScanCount() == 1) {
					updateAndClearFilter()
				} else {
					update()
				}
			}
			.setNegativeButton(android.R.string.cancel) { _, _ ->
			}
			.show()
	}

	private fun Context.askToRemoveScans() {
		MaterialAlertDialogBuilder(this)
			.setMessage(
				if (filter == null) {
					R.string.really_remove_all_scans
				} else {
					R.string.really_remove_selected_scans
				}
			)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				db.removeScans(filter, groupFilter)
				updateAndClearFilter()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ ->
			}
			.show()
	}

	private fun askToExportToFile() {
		scope.launch {
			val ac = this@HistoryActivity
			progressView.useVisibility {
				// Write permission is only required before Android Q.
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
					!ac.hasWritePermission { askToExportToFile() }
				) {
					return@useVisibility
				}
				val options = ac.resources.getStringArray(
					R.array.export_options_values
				)
				val delimiter = alertDialog<String>(ac) { resume ->
					setTitle(R.string.export_as)
					setItems(R.array.export_options_names) { _, which ->
						resume(options[which])
					}
				} ?: return@useVisibility
				val name = withContext(Dispatchers.Main) {
					ac.askForFileName(
						when (delimiter) {
							"db" -> ".db"
							"json" -> ".json"
							else -> ".csv"
						}
					)
				} ?: return@useVisibility
				val message = when (delimiter) {
					"db" -> ac.exportDatabase(name)
					else -> db.getScansDetailed(filter, groupFilter).use {
						when (delimiter) {
							"json" -> ac.exportJson(name, it)
							else -> ac.exportCsv(name, it, delimiter)
						}
					}
				}.toSaveResult()
				withContext(Dispatchers.Main) {
					ac.toast(message)
				}
			}
		}
	}

	private fun Context.pickListSeparatorAndShare(asFile: Boolean) {
		val separators = resources.getStringArray(
			R.array.list_separators_values
		)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.pick_list_separator)
			.setItems(R.array.list_separators_names) { _, which ->
				shareScans(separators[which], asFile)
			}
			.show()
	}

	private fun shareScans(format: String, asFile: Boolean) = scope.launch {
		progressView.useVisibility {
			val selectedIds = scansAdapter?.getSelectedIds()
			if (selectedIds?.isNotEmpty() == true) {
				db.getScansDetailed(selectedIds.toLongArray())
			} else {
				db.getScansDetailed(filter, groupFilter)
			}.use { cursor ->
				val details = format.split(":")
				when (details[0]) {
					"text" -> Pair(cursor.exportText(details[1]), "txt")
					"csv" -> Pair(cursor.exportCsv(details[1]), "csv")
					else -> Pair(cursor.exportJson(), "json")
				}
			}.let { (text, ext) ->
				text?.let {
					withContext(Dispatchers.Main) {
						if (asFile) {
							this@HistoryActivity.shareAsFile(
								it,
								String.format("scans.%s", ext)
							)
						} else {
							this@HistoryActivity.shareText(it)
						}
					}
				}
			}
		}
	}

	private fun askToFilter() {
		scope.launch {
			val groups = sortedGroups(readGroups())
			withContext(Dispatchers.Main) {
				val names = mutableListOf(
					getString(R.string.all_groups),
					getString(R.string.no_group)
				)
				names.addAll(groups.map { it.name })
				MaterialAlertDialogBuilder(this@HistoryActivity)
					.setTitle(R.string.filter_by_group)
					.setItems(names.toTypedArray()) { _, which ->
						groupFilter = when (which) {
							0 -> null
							1 -> 0L
							else -> groups[which - 2].id
						}
						update()
					}
					.show()
			}
		}
	}

	/**
	 * Opens the barcode composer in remark mode for the DI code of the
	 * most recent scan.
	 */
	private fun askToAddDiCodeRemark(ids: List<Long> = emptyList()) {
		scope.launch {
			// Any scan works as the target now: one without a DI code
			// just doesn't update a DI code remark.
			val content = db.getLastScanContent(ids)
			withContext(Dispatchers.Main) {
				if (content == null) {
					toast(R.string.history_empty)
				} else {
					startActivity(
						Intent(
							this@HistoryActivity,
							EncodeActivity::class.java
						).apply {
							putExtra(EncodeActivity.EXTRA_REMARK_MODE, true)
							putExtra(
								EncodeActivity.EXTRA_TARGET_CONTENT,
								content
							)
						}
					)
				}
			}
		}
	}

	/**
	 * The DI code switch and the DI code remarks live behind the
	 * overflow menu of the history so they are only a tap away from
	 * the list they affect.
	 */
	private fun askToShowAdvancedFeatures() {
		val detection = prefs.diCodeDetection
		val items = arrayOf(
			getString(
				if (detection) {
					R.string.di_code_detection_on
				} else {
					R.string.di_code_detection_off
				}
			),
			getString(R.string.di_code_semi_auto_remark_default),
			getString(R.string.di_code_semi_auto_remark_all),
			getString(R.string.di_code_remarks)
		)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.advanced_features)
			.setItems(items) { _, which ->
				when (which) {
					0 -> {
						prefs.diCodeDetection = !detection
						update()
					}

					1 -> applySemiAutoDiCodeRemarks(
						onlyDefaultGroup = true
					)

					2 -> applySemiAutoDiCodeRemarks(
						onlyDefaultGroup = false
					)

					3 -> startActivity(
						Intent(this, DiCodeRemarksActivity::class.java)
					)
				}
			}
			.show()
	}

	/**
	 * Walks the history once (on demand, never while scanning) and
	 * remarks every DI code that is followed by a non-DI entry and that
	 * has no remark yet.
	 */
	private fun applySemiAutoDiCodeRemarks(onlyDefaultGroup: Boolean) {
		scope.launch {
			val contents = db.getScanContents(onlyDefaultGroup)
			// Everything below touches preferences and UI, so it has to
			// happen on the main thread: scope runs on Dispatchers.IO.
			withContext(Dispatchers.Main) {
				val remarks = DiCode.remarksFor(contents) {
					prefs.diCodeRemark(it) != null
				}
				if (remarks.isNotEmpty()) {
					prefs.setDiCodeRemarks(remarks)
				}
				toast(
					if (remarks.isEmpty()) {
						getString(R.string.di_code_semi_auto_remark_none)
					} else {
						getString(
							R.string.di_code_semi_auto_remark_done,
							remarks.size
						)
					}
				)
				update()
			}
		}
	}

	private fun askToManageGroups() {
		scope.launch {
			val groups = sortedGroups(readGroups())
			withContext(Dispatchers.Main) {
				val limited = prefs.limitGroups
				val listed = if (limited) {
					groups.take(ScansAdapter.GROUP_LIMIT)
				} else {
					groups
				}
				val names = mutableListOf(
					getString(R.string.new_group),
					getString(R.string.sort_groups),
					getString(
						if (limited) {
							R.string.show_all_groups
						} else {
							R.string.show_recent_groups
						}
					),
					getString(R.string.group_other)
				)
				names.addAll(listed.map { it.name })
				MaterialAlertDialogBuilder(this@HistoryActivity)
					.setTitle(R.string.manage_groups)
					.setItems(names.toTypedArray()) { _, which ->
						when {
							which == 0 -> askToCreateGroup(groups)
							which == 1 -> askToSetSortOrder()
							which == 2 -> {
								prefs.limitGroups = !limited
								update()
								askToManageGroups()
							}
							which == 3 -> {
								groupFilter = 0L
								update()
							}
							else -> askToEditGroup(listed[which - 4])
						}
					}
					.show()
			}
		}
	}

	private fun askToSetSortOrder() {
		val orders = listOf(
			R.string.sort_time_desc to "time_desc",
			R.string.sort_time_asc to "time_asc",
			R.string.sort_name_asc to "name_asc",
			R.string.sort_name_desc to "name_desc"
		)
		val current = orders.indexOfFirst {
			it.second == prefs.groupSortOrder
		}.coerceAtLeast(0)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.sort_groups)
			.setSingleChoiceItems(
				orders.map { getString(it.first) }.toTypedArray(),
				current
			) { dialog, which ->
				prefs.groupSortOrder = orders[which].second
				dialog.dismiss()
				update()
			}
			.show()
	}

	private fun askToCreateGroup(groups: List<ScanGroup>) {
		askForGroupName(null) { name ->
			val color = GROUP_COLORS[groups.size % GROUP_COLORS.size]
			if (db.insertGroup(name, color) < 0L) {
				toast(R.string.group_name_exists)
			}
			update()
		}
	}

	private fun askToEditGroup(group: ScanGroup) {
		MaterialAlertDialogBuilder(this)
			.setTitle(group.name)
			.setItems(
				arrayOf(
					getString(
						if (group.pinned) {
							R.string.unpin_group
						} else {
							R.string.pin_group
						}
					),
					getString(R.string.rename),
					getString(R.string.delete_group)
				)
			) { _, which ->
				when (which) {
					0 -> {
						db.setGroupPinned(group.id, !group.pinned)
						update()
					}

					1 -> askForGroupName(group.name) { name ->
						if (!db.renameGroup(group.id, name)) {
							toast(R.string.group_name_exists)
						}
						update()
					}

					else -> askToDeleteGroup(group)
				}
			}
			.show()
	}

	private fun askToDeleteGroup(group: ScanGroup) {
		MaterialAlertDialogBuilder(this)
			.setMessage(R.string.delete_group_confirm)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				db.removeGroup(group.id)
				if (groupFilter == group.id) {
					groupFilter = null
				}
				update()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.show()
	}

	@SuppressLint("InflateParams")
	private fun askForGroupName(initial: String?, onOk: (String) -> Unit) {
		val view = LayoutInflater.from(this).inflate(
			R.layout.dialog_enter_group_name, null
		)
		val nameView = view.findViewById<EditText>(R.id.name)
		nameView.setText(initial)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.enter_group_name)
			.setView(view)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val name = nameView.text.toString().trim()
				if (name.isNotEmpty()) {
					onOk(name)
				}
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.show()
	}

	private fun assignToGroup() {
		val ids = scansAdapter?.getSelectedIds()?.toList() ?: return
		if (ids.isEmpty()) {
			return
		}
		scope.launch {
			val groups = sortedGroups(readGroups())
			withContext(Dispatchers.Main) {
				val dateName = SimpleDateFormat(
					"yyyyMMdd",
					Locale.US
				).format(Date())
				val names = mutableListOf(
					getString(R.string.new_group_with_date, dateName)
				)
				names.add(getString(R.string.no_group))
				names.addAll(groups.map { it.name })
				MaterialAlertDialogBuilder(this@HistoryActivity)
					.setTitle(R.string.assign_to_group)
					.setItems(names.toTypedArray()) { _, which ->
						when {
							which == 0 ->
								scope.launch {
									val existing = groups.firstOrNull {
										it.name == dateName
									}
									val gid = existing?.id ?: db.insertGroup(
										dateName,
										GROUP_COLORS[
											groups.size % GROUP_COLORS.size
										]
									)
									if (gid < 0L) {
										withContext(Dispatchers.Main) {
											toast(R.string.group_name_exists)
										}
									} else {
										assignScansToGroup(ids, gid)
									}
								}

							which == 1 ->
								assignScansToGroup(ids, null)

							else ->
								assignScansToGroup(ids, groups[which - 2].id)
						}
					}
					.show()
			}
		}
	}

	private fun assignScansToGroup(ids: List<Long>, gid: Long?) {
		scope.launch {
			ids.forEach { db.setScanGroup(it, gid) }
			withContext(Dispatchers.Main) {
				closeActionMode()
				update()
			}
		}
	}

	private fun readGroups(): List<ScanGroup> = db.getGroups().use { c ->
		val list = mutableListOf<ScanGroup>()
		val idIndex = c.getColumnIndex(Database.GROUPS_ID)
		val nameIndex = c.getColumnIndex(Database.GROUPS_NAME)
		val colorIndex = c.getColumnIndex(Database.GROUPS_COLOR)
		val createdIndex = c.getColumnIndex(Database.GROUPS_CREATED)
		val pinnedIndex = c.getColumnIndex(Database.GROUPS_PINNED)
		while (c.moveToNext()) {
			list.add(
				ScanGroup(
					c.getLong(idIndex),
					c.getString(nameIndex) ?: "",
					c.getInt(colorIndex),
					createdIndex.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
					pinnedIndex.takeIf { it >= 0 }?.let { c.getInt(it) != 0 }
						?: false
				)
			)
		}
		list
	}

	/**
	 * Orders groups exactly like the history list does: pinned groups
	 * come first, then the order picked in "Sort groups".
	 */
	private fun sortedGroups(groups: List<ScanGroup>): List<ScanGroup> {
		val collator = Collator.getInstance(Locale.getDefault())
		return groups.sortedWith(
			Comparator { a, b ->
				when {
					a.pinned && b.pinned -> compareGroupOrder(a, b, collator)
					a.pinned -> -1
					b.pinned -> 1
					else -> compareGroupOrder(a, b, collator)
				}
			}
		)
	}

	private fun compareGroupOrder(
		a: ScanGroup,
		b: ScanGroup,
		collator: Collator
	): Int = when (prefs.groupSortOrder) {
		"time_asc" -> a.created.compareTo(b.created)
		"name_asc" -> collator.compare(a.name, b.name)
		"name_desc" -> collator.compare(b.name, a.name)
		else -> b.created.compareTo(a.created)
	}

	private fun exportAsTextArchive() {
		scope.launch {
			val text = db.getScans(filter, groupFilter).use {
				it.exportText("\n")
			}
			writeTextArchive(text)
		}
	}

	private fun exportSelectedAsTextArchive() {
		val text = scansAdapter?.getSelectedContent("\n") ?: return
		if (text.isEmpty()) {
			return
		}
		closeActionMode()
		scope.launch {
			writeTextArchive(text)
		}
	}

	private suspend fun writeTextArchive(text: String) {
		val name = SimpleDateFormat(
			"yyyyMMdd-HHmmss",
			Locale.US
		).format(Date()) + ".txt"
		val archived = ArchiveStorage(this@HistoryActivity).write(name, text)
		val exported = writeExternalFileTo(
			name,
			"text/plain",
			"BinaryEye"
		) {
			it.write(text.toByteArray())
		}
		withContext(Dispatchers.Main) {
			toast(
				if (archived || exported) {
					R.string.export_saved
				} else {
					R.string.error_saving_file
				}
			)
		}
	}

	private fun askToShowExports() {
		scope.launch {
			val storage = ArchiveStorage(this@HistoryActivity)
			val files = storage.list()
			withContext(Dispatchers.Main) {
				showExportsDialog(storage, files)
			}
		}
	}

	@SuppressLint("InflateParams")
	private fun showExportsDialog(
		storage: ArchiveStorage,
		files: List<ArchiveStorage.Entry>
	) {
		val view = LayoutInflater.from(this).inflate(
			R.layout.dialog_exports, null
		)
		val dirValue = view.findViewById<TextView>(R.id.export_dir_value)
		val hintView = view.findViewById<TextView>(R.id.exports_hint)
		val listView = view.findViewById<RecyclerView>(R.id.exports_list)

		exportsAdapter = ExportsAdapter(
			onOpen = { entry -> askToHandleExportEntry(entry) },
			onRename = { entry -> askToRenameExportEntry(entry) }
		).also { adapter ->
			adapter.submit(files)
			listView.layoutManager = LinearLayoutManager(this)
			listView.adapter = adapter
		}

		val multiToggle = view.findViewById<TextView>(
			R.id.export_multiselect_toggle
		)
		val selectionBar = view.findViewById<View>(R.id.export_selection_bar)
		val selectAllView = view.findViewById<TextView>(R.id.export_select_all)
		val selectionCountView = view.findViewById<TextView>(
			R.id.export_selection_count
		)
		val deleteSelectedView = view.findViewById<TextView>(
			R.id.export_delete_selected
		)
		fun updateSelectionUi() {
			val adapter = exportsAdapter ?: return
			val inSelection = adapter.isSelectionMode()
			selectionCountView.text = getString(
				R.string.selected_count,
				adapter.getSelectedCount()
			)
			multiToggle.setText(
				if (inSelection) {
					R.string.exit_multiselect
				} else {
					R.string.multiselect
				}
			)
			selectionBar.visibility = if (inSelection) {
				View.VISIBLE
			} else {
				View.GONE
			}
			deleteSelectedView.isEnabled = adapter.getSelectedCount() > 0
		}
		multiToggle.setOnClickListener {
			val adapter = exportsAdapter ?: return@setOnClickListener
			adapter.setSelectionMode(!adapter.isSelectionMode())
			updateSelectionUi()
		}
		selectAllView.setOnClickListener {
			exportsAdapter?.selectAll()
			updateSelectionUi()
		}
		deleteSelectedView.setOnClickListener {
			val entries = exportsAdapter?.getSelectedEntries()
				?: return@setOnClickListener
			if (entries.isEmpty()) {
				return@setOnClickListener
			}
			askToDeleteExportEntries(entries)
		}
		updateSelectionUi()

		exportsHintView = hintView
		exportsDirValueView = dirValue
		dirValue.text = storage.currentLabel()
		hintView.setText(
			if (files.isEmpty()) R.string.no_exports
			else R.string.archive_list_hint
		)

		ItemTouchHelper(
			object : ItemTouchHelper.SimpleCallback(
				0,
				ItemTouchHelper.LEFT
			) {
				override fun isItemViewSwipeEnabled(): Boolean =
					!(exportsAdapter?.isSelectionMode() ?: false)

				override fun onMove(
					recyclerView: RecyclerView,
					viewHolder: RecyclerView.ViewHolder,
					target: RecyclerView.ViewHolder
				) = false

				override fun onSwiped(
					viewHolder: RecyclerView.ViewHolder,
					direction: Int
				) {
					val position = viewHolder.bindingAdapterPosition
					if (position == RecyclerView.NO_POSITION) {
						return
					}
					val entry = exportsAdapter?.itemAt(position)
						?: return
					askToDeleteExportEntry(entry) {
						exportsAdapter?.notifyDataSetChanged()
					}
				}

				override fun onChildDraw(
					c: android.graphics.Canvas,
					recyclerView: RecyclerView,
					viewHolder: RecyclerView.ViewHolder,
					dX: Float,
					dY: Float,
					actionState: Int,
					isCurrentlyActive: Boolean
				) {
					viewHolder.itemView
						.findViewById<View>(R.id.export_foreground)
						.translationX = dX
				}

				override fun clearView(
					recyclerView: RecyclerView,
					viewHolder: RecyclerView.ViewHolder
				) {
					viewHolder.itemView
						.findViewById<View>(R.id.export_foreground)
						.translationX = 0f
				}
			}
		).attachToRecyclerView(listView)

		view.findViewById<View>(R.id.export_dir_row)
			.setOnClickListener {
				askToConfigureExportDirectory()
			}

		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.export_archive)
			.setView(view)
			.setPositiveButton(android.R.string.ok, null)
			.setOnDismissListener {
				exportsAdapter = null
				exportsHintView = null
				exportsDirValueView = null
			}
			.show()
	}

	private fun refreshExports() {
		val storage = ArchiveStorage(this)
		scope.launch {
			val files = storage.list()
			val label = storage.currentLabel()
			withContext(Dispatchers.Main) {
				exportsAdapter?.submit(files)
				exportsDirValueView?.text = label
				exportsHintView?.setText(
					if (files.isEmpty()) R.string.no_exports
					else R.string.archive_list_hint
				)
			}
		}
	}

	private fun askToConfigureExportDirectory() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.export_directory)
			.setItems(
				arrayOf(
					getString(R.string.change_export_directory),
					getString(R.string.reset_export_directory)
				)
			) { _, which ->
				when (which) {
					0 -> exportDirectoryPicker.launch(null)
					else -> {
						prefs.exportDirectoryUri = ""
						toast(R.string.directory_changed)
						refreshExports()
					}
				}
			}
			.show()
	}

	private fun askToHandleExportEntry(entry: ArchiveStorage.Entry) {
		MaterialAlertDialogBuilder(this)
			.setTitle(entry.name)
			.setItems(
				arrayOf(
					getString(R.string.share_to_apps),
					getString(R.string.export_to_sd),
					getString(R.string.delete_file)
				)
			) { _, which ->
				when (which) {
					0 -> shareExportEntry(entry)
					1 -> exportToSd(entry)
					else -> askToDeleteExportEntry(entry)
				}
			}
			.show()
	}

	private fun shareExportEntry(entry: ArchiveStorage.Entry) {
		scope.launch {
			val bytes = entry.openInputStream()?.use { it.readBytes() }
			if (bytes == null) {
				withContext(Dispatchers.Main) {
					toast(R.string.error_saving_file)
				}
			} else {
				withContext(Dispatchers.Main) {
					shareAsFile(bytes, entry.name)
				}
			}
		}
	}

	private fun exportToSd(entry: ArchiveStorage.Entry) {
		scope.launch {
			val ok = entry.openInputStream()?.let { input ->
				writeExternalFileTo(entry.name, "text/plain", "BinaryEye") { output ->
					input.use { it.copyTo(output) }
				}
			} ?: false
			withContext(Dispatchers.Main) {
				toast(
					if (ok) {
						R.string.saved_in_downloads
					} else {
						R.string.error_saving_file
					}
				)
			}
		}
	}

	@SuppressLint("InflateParams")
	private fun askToRenameExportEntry(entry: ArchiveStorage.Entry) {
		val view = LayoutInflater.from(this).inflate(
			R.layout.dialog_enter_file_name, null
		)
		val nameView = view.findViewById<EditText>(R.id.name)
		val baseName = entry.name.removeSuffix(".txt")
		nameView.setText(baseName)
		nameView.setSelection(baseName.length)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.rename_file)
			.setView(view)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val newName = nameView.text.toString().trim()
				if (newName.isEmpty()) {
					return@setPositiveButton
				}
				val fullName = if (
					newName.endsWith(".txt", ignoreCase = true)
				) {
					newName
				} else {
					"$newName.txt"
				}
				if (fullName == entry.name) {
					return@setPositiveButton
				}
				if (entry.renameTo(fullName)) {
					refreshExports()
				} else {
					toast(R.string.file_name_exists)
				}
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.show()
	}

	private fun askToDeleteExportEntry(
		entry: ArchiveStorage.Entry,
		onCancel: (() -> Unit)? = null
	) {
		MaterialAlertDialogBuilder(this)
			.setMessage(R.string.delete_file_confirm)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				if (entry.delete()) {
					toast(R.string.file_deleted)
					refreshExports()
				} else {
					toast(R.string.error_saving_file)
					onCancel?.invoke()
				}
			}
			.setNegativeButton(android.R.string.cancel) { _, _ ->
				onCancel?.invoke()
			}
			.show()
	}

	private fun askToDeleteExportEntries(entries: List<ArchiveStorage.Entry>) {
		if (entries.isEmpty()) {
			return
		}
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.delete_files_confirm, entries.size))
			.setPositiveButton(android.R.string.ok) { _, _ ->
				entries.forEach { it.delete() }
				exportsAdapter?.setSelectionMode(false)
				toast(R.string.file_deleted)
				refreshExports()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.show()
	}
}

private fun Cursor.exportText(separator: String): String {
	val sb = StringBuilder()
	val contentIndex = getColumnIndex(Database.SCANS_TEXT)
	if (contentIndex > -1 && moveToFirst()) {
		do {
			val content = getString(contentIndex)
			if (content?.isNotEmpty() == true) {
				sb.append(content)
				sb.append(separator)
			}
		} while (moveToNext())
	}
	return sb.toString()
}

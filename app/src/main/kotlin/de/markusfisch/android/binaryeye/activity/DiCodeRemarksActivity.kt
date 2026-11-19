package de.markusfisch.android.binaryeye.activity

import android.app.Dialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.app.db
import de.markusfisch.android.binaryeye.app.prefs
import de.markusfisch.android.binaryeye.view.setPaddingFromWindowInsets
import de.markusfisch.android.binaryeye.view.systemBarListViewScrollListener

/**
 * Lets the user add a remark to every DI code that is in the history.
 */
class DiCodeRemarksActivity : AbstractBaseActivity() {
	private val allCodes = ArrayList<String>()
	private val diCodes = ArrayList<String>()

	private lateinit var adapter: DiCodeAdapter
	private lateinit var noRemarksView: TextView

	private var query = ""

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setScreenContentView(R.layout.activity_di_code_remarks)
		setTitle(R.string.di_code_remarks)

		allCodes.addAll(readDiCodes())
		diCodes.addAll(allCodes)

		val listView = findViewById<ListView>(R.id.remarks)
		noRemarksView = findViewById(R.id.no_remarks)
		listView.emptyView = noRemarksView
		updateEmptyView()
		adapter = DiCodeAdapter(diCodes)
		listView.adapter = adapter
		listView.setOnScrollListener(systemBarListViewScrollListener)
		listView.setOnItemClickListener { _, _, position, _ ->
			editRemark(position)
		}

		findViewById<View>(R.id.inset_layout)
			.setPaddingFromWindowInsets(bottom = false)
		listView.setPaddingFromWindowInsets()
	}

	override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
		inflater.inflate(R.menu.di_code_remarks, menu)
		val item = menu.findItem(R.id.search) ?: return
		val searchView = item.actionView as? SearchView ?: return
		searchView.queryHint = getString(R.string.search_di_code_remarks)
		searchView.setOnQueryTextListener(
			object : SearchView.OnQueryTextListener {
				override fun onQueryTextSubmit(text: String): Boolean {
					applyFilter(text)
					return false
				}

				override fun onQueryTextChange(text: String): Boolean {
					applyFilter(text)
					return false
				}
			}
		)
	}

	private fun readDiCodes(): List<String> {
		val codes = LinkedHashSet<String>()
		codes.addAll(db.getDiCodes())
		// Keep remarks for codes that are no longer in the history.
		codes.addAll(prefs.diCodeRemarks.keys.sorted())
		return codes.toList()
	}

	/**
	 * Filters [allCodes] by [text], matching both the DI code itself and
	 * its remark.
	 */
	private fun applyFilter(text: String) {
		val filter = text.trim()
		query = filter
		diCodes.clear()
		if (filter.isEmpty()) {
			diCodes.addAll(allCodes)
		} else {
			allCodes.filterTo(diCodes) { diCode ->
				diCode.contains(filter, true) ||
					prefs.diCodeRemark(diCode)
						?.contains(filter, true) == true
			}
		}
		adapter.notifyDataSetChanged()
		updateEmptyView()
	}

	private fun updateEmptyView() {
		noRemarksView.setText(
			if (query.isEmpty()) {
				R.string.di_code_remarks_empty
			} else {
				R.string.di_code_remarks_no_match
			}
		)
	}

	private fun editRemark(position: Int) {
		val diCode = diCodes[position]
		val remark = prefs.diCodeRemark(diCode).orEmpty()
		val view = layoutInflater.inflate(R.layout.dialog_di_code_remark, null)
		view.findViewById<TextView>(R.id.di_code).text = diCode
		val remarkView = view.findViewById<EditText>(R.id.remark)
		remarkView.setText(remark)
		remarkView.setSelection(remark.length)

		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.di_code_remark_edit)
			.setView(view)
			.setPositiveButton(android.R.string.ok, null)
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.create()

		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener {
					saveRemark(diCode, remarkView, dialog)
				}
		}

		dialog.show()
	}

	private fun saveRemark(diCode: String, remarkView: EditText, dialog: Dialog) {
		prefs.setDiCodeRemark(diCode, remarkView.text.toString())
		// The remark is part of the filter, so the list may change.
		applyFilter(query)
		dialog.dismiss()
	}

	private inner class DiCodeAdapter(
		items: List<String>
	) : ArrayAdapter<String>(
		this,
		android.R.layout.simple_list_item_2,
		android.R.id.text1,
		items
	) {
		override fun getView(
			position: Int,
			convertView: View?,
			parent: ViewGroup
		): View {
			val view = super.getView(position, convertView, parent)
			val diCode = diCodes[position]
			val remark = prefs.diCodeRemark(diCode)
			view.findViewById<TextView>(android.R.id.text1).text = diCode
			view.findViewById<TextView>(android.R.id.text2).text =
				remark ?: getString(R.string.di_code_remarks_none)
			return view
		}
	}
}

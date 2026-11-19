package de.markusfisch.android.binaryeye.activity

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
	private val diCodes = ArrayList<String>()

	private lateinit var adapter: DiCodeAdapter

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setScreenContentView(R.layout.activity_di_code_remarks)
		setTitle(R.string.di_code_remarks)

		diCodes.addAll(readDiCodes())

		val listView = findViewById<ListView>(R.id.remarks)
		listView.emptyView = findViewById(R.id.no_remarks)
		findViewById<TextView>(R.id.no_remarks).setText(
			R.string.di_code_remarks_empty
		)
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

	private fun readDiCodes(): List<String> {
		val codes = LinkedHashSet<String>()
		codes.addAll(db.getDiCodes())
		// Keep remarks for codes that are no longer in the history.
		codes.addAll(prefs.diCodeRemarks.keys.sorted())
		return codes.toList()
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
		adapter.notifyDataSetChanged()
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

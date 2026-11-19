package de.markusfisch.android.binaryeye.adapter

import android.content.Context
import android.database.MatrixCursor
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.content.ParsedContentType
import de.markusfisch.android.binaryeye.database.Database
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Fork specific: the history adapter is a BaseAdapter that materializes its
 * rows while reading the cursor, so these tests bind the first scan row
 * instead of calling the CursorAdapter's newView/bindView pair.
 */
@RunWith(RobolectricTestRunner::class)
class ScansAdapterTest {
	private val context: Context = RuntimeEnvironment.getApplication()
	private val plain = "01095060001343521726123110LOT-7\u001d21SERIAL-9"
	private val hri = "(01)09506000134352(17)261231(10)LOT-7(21)SERIAL-9"

	@Test
	fun showsGtinForGs1TextAndPreservesStoredContent() {
		for (text in listOf(plain, hri)) {
			cursor(text, ParsedContentType.GS1_DATA, raw = text.toByteArray())
				.use { cursor ->
					val adapter = adapter(cursor)
					val (view, position) = bindScan(adapter)

					assertEquals(
						"09506000134352",
						view.findViewById<TextView>(R.id.content).text.toString()
					)
					assertEquals(text, adapter.getContent(position))
				}
		}
	}

	@Test
	fun recognizesGs1InLegacyUnknownHistoryEntries() {
		for (text in listOf(plain, hri)) {
			cursor(text, ParsedContentType.UNKNOWN, raw = ByteArray(0))
				.use { cursor ->
					val adapter = adapter(cursor)
					val (view, position) = bindScan(adapter)

					assertEquals(
						"09506000134352",
						view.findViewById<TextView>(R.id.content).text.toString()
					)
					assertEquals(
						context.getString(
							R.string.scan_meta_data,
							"Data Matrix",
							context.getString(R.string.parsed_type_gs1_data)
						),
						view.findViewById<TextView>(R.id.format).text.toString()
					)
					assertEquals(text, adapter.getContent(position))
				}
		}
	}

	@Test
	fun keepsLabelsAheadOfGs1Preview() {
		for (
			type in listOf(
				ParsedContentType.GS1_DATA,
				ParsedContentType.UNKNOWN
			)
		) {
			cursor(hri, type, "My medicine").use { cursor ->
				val adapter = adapter(cursor)
				val (view, _) = bindScan(adapter)

				assertEquals(
					"My medicine",
					view.findViewById<TextView>(R.id.content).text.toString()
				)
			}
		}
	}

	@Test
	fun preservesOtherTextAndBinaryPreviews() {
		for ((text, raw, expected) in listOf(
			Triple("https://example.com/path", ByteArray(0), "https://example.com/path"),
			Triple("", byteArrayOf(0, 1, 2), "000102")
		)) {
			cursor(text, ParsedContentType.UNKNOWN, raw = raw).use { cursor ->
				val adapter = adapter(cursor)
				val (view, _) = bindScan(adapter)

				assertEquals(
					expected,
					view.findViewById<TextView>(R.id.content).text.toString()
				)
			}
		}
	}

	private fun adapter(cursor: MatrixCursor) = ScansAdapter(
		context,
		cursor,
		emptyList(),
		"time_desc",
		false,
		mutableSetOf(ScansAdapter.DEFAULT_GROUP_KEY),
		{ _, _ -> }
	)

	private fun bindScan(adapter: ScansAdapter): Pair<View, Int> {
		val position = (0 until adapter.count).first { !adapter.isHeader(it) }
		val view = adapter.getView(position, null, FrameLayout(context))
		return view to position
	}

	private fun cursor(
		text: String,
		type: ParsedContentType,
		name: String? = null,
		raw: ByteArray = plain.toByteArray()
	) = MatrixCursor(
		arrayOf(
			Database.SCANS_ID,
			Database.SCANS_PINNED,
			Database.SCANS_DATETIME,
			Database.SCANS_NAME,
			Database.SCANS_TEXT,
			Database.SCANS_RAW,
			Database.SCANS_CONTENT_TYPE,
			Database.SCANS_FORMAT
		)
	).apply {
		addRow(
			arrayOf(
				1L,
				0,
				"2026-11-15 12:00:00",
				name,
				text,
				raw,
				type.name,
				"DataMatrix"
			)
		)
	}
}

package de.markusfisch.android.binaryeye.content

import android.content.Context
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.app.prefs

/**
 * GS1 application identifier "01" starts a GTIN-14 that is used here
 * as the DI code of a data carrier.
 */
object DiCode {
	private const val PREFIX = "01"
	const val LENGTH = 14

	// "01" + 14 digits.
	private const val MIN_LENGTH = 16

	/**
	 * Returns the 14 digits that follow a leading "01", or null when
	 * [content] doesn't start with "01" or has less than 16 chars.
	 */
	fun of(content: String): String? {
		if (content.length < MIN_LENGTH ||
			!content.startsWith(PREFIX)
		) {
			return null
		}
		return content.substring(PREFIX.length, MIN_LENGTH)
	}

	/**
	 * The remark that a scan following a DI code suggests: everything
	 * before the first space, "x" or "X". Null when there is nothing
	 * left or when the content is another DI code.
	 */
	fun remarkOf(content: String): String? {
		if (of(content) != null) {
			return null
		}
		val remark = content.takeWhile {
			it != ' ' && it != 'x' && it != 'X'
		}.trim()
		return remark.ifEmpty { null }
	}

	/**
	 * Walks [contents] (chronological order) and returns a remark for
	 * every DI code that is followed by a non-DI entry and that has no
	 * remark yet, according to [hasRemark].
	 */
	fun remarksFor(
		contents: List<String>,
		hasRemark: (String) -> Boolean
	): Map<String, String> {
		val remarks = LinkedHashMap<String, String>()
		contents.forEachIndexed { index, content ->
			val diCode = of(content) ?: return@forEachIndexed
			if (remarks.containsKey(diCode) || hasRemark(diCode)) {
				return@forEachIndexed
			}
			val remark = contents.getOrNull(index + 1)
				?.let { remarkOf(it) } ?: return@forEachIndexed
			remarks[diCode] = remark
		}
		return remarks
	}
}

/**
 * The line to show for [diCode]: the DI code itself and, if there is
 * one, its remark in brackets.
 */
fun Context.diCodeLine(diCode: String): String {
	val remark = prefs.diCodeRemark(diCode)
	return if (remark.isNullOrEmpty()) {
		getString(R.string.di_code_line, diCode)
	} else {
		getString(R.string.di_code_line_with_remark, diCode, remark)
	}
}

package de.markusfisch.android.binaryeye.database

import android.content.Context
import android.os.Environment
import de.markusfisch.android.binaryeye.io.ExportPaths
import java.io.File

/**
 * Copies the database into every configured export path. Returns null
 * when there is no database file to export.
 */
fun Context.exportDatabase(fileName: String): ExportPaths.Report? {
	val dbFile = File(
		Environment.getDataDirectory(),
		"//data//${packageName}//databases//${Database.FILE_NAME}"
	)
	if (!dbFile.exists()) {
		return null
	}
	val bytes = try {
		dbFile.readBytes()
	} catch (_: Exception) {
		return null
	}
	return ExportPaths.write(
		this,
		fileName,
		"application/vnd.sqlite3",
		bytes
	)
}

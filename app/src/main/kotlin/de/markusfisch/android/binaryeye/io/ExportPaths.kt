package de.markusfisch.android.binaryeye.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.app.prefs

/**
 * Every export of this fork ends up in the export paths configured in
 * the advanced features of the history. Up to [MAX_PATHS] directories
 * can be added and each export is written to all of them, so a single
 * export action always produces the same set of files.
 *
 * Write failures are reported per path, so callers can tell a missing
 * storage permission apart from a full or vanished directory.
 */
object ExportPaths {
	const val MAX_PATHS = 2

	private val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
		Intent.FLAG_GRANT_WRITE_URI_PERMISSION

	class Target(
		val uri: Uri,
		val label: String,
		val hasPermission: Boolean
	)

	class Report(
		val configured: Int,
		val saved: Int,
		val failed: Int,
		val missingPermission: Boolean
	) {
		val isSaved: Boolean
			get() = configured > 0 && saved == configured && failed == 0

		/** Resource id of the toast that describes this result. */
		fun messageRes(): Int = when {
			configured == 0 -> R.string.export_paths_missing
			missingPermission -> R.string.export_paths_no_permission
			isSaved -> R.string.export_saved_to_paths
			saved > 0 -> R.string.export_paths_partial
			else -> R.string.error_saving_file
		}
	}

	private enum class Outcome { SAVED, NO_PERMISSION, FAILED }

	fun targets(context: Context): List<Target> =
		prefs.exportDirectoryUris.map { value ->
			val uri = Uri.parse(value)
			Target(
				uri,
				labelOf(context, uri),
				hasPermission(context, uri)
			)
		}

	/** Adds [uri] as export path, keeping the persistable grant. */
	fun add(context: Context, uri: Uri): Boolean {
		val uris = prefs.exportDirectoryUris.map { Uri.parse(it) }
		if (uris.size >= MAX_PATHS || uris.any { it == uri }) {
			return false
		}
		try {
			context.contentResolver.takePersistableUriPermission(uri, FLAGS)
		} catch (_: SecurityException) {
			// Keep the path anyway: writing may still work for this
			// session, and the export report complains when it doesn't.
		}
		prefs.exportDirectoryUris = (uris + uri).map { it.toString() }
		return true
	}

	fun remove(context: Context, uri: Uri) {
		val uris = prefs.exportDirectoryUris.map { Uri.parse(it) }
		if (uris.none { it == uri }) {
			return
		}
		try {
			context.contentResolver.releasePersistableUriPermission(uri, FLAGS)
		} catch (_: SecurityException) {
			// Nothing to release then.
		}
		prefs.exportDirectoryUris = uris
			.filterNot { it == uri }
			.map { it.toString() }
	}

	/** Writes [bytes] to every configured export path. */
	fun write(
		context: Context,
		fileName: String,
		mimeType: String,
		bytes: ByteArray
	): Report {
		val targets = targets(context)
		var saved = 0
		var failed = 0
		var missingPermission = false
		targets.forEach { target ->
			if (!target.hasPermission) {
				missingPermission = true
				failed++
				return@forEach
			}
			when (writeTo(context, target, fileName, mimeType, bytes)) {
				Outcome.SAVED -> saved++

				Outcome.NO_PERMISSION -> {
					missingPermission = true
					failed++
				}

				Outcome.FAILED -> failed++
			}
		}
		return Report(targets.size, saved, failed, missingPermission)
	}

	private fun writeTo(
		context: Context,
		target: Target,
		fileName: String,
		mimeType: String,
		bytes: ByteArray
	): Outcome {
		val tree = DocumentFile.fromTreeUri(context, target.uri)
			?: return Outcome.FAILED
		if (!tree.exists() || !tree.isDirectory) {
			return Outcome.FAILED
		}
		val created: DocumentFile? = try {
			tree.createFile(mimeType, uniqueName(tree, fileName))
		} catch (_: SecurityException) {
			return Outcome.NO_PERMISSION
		} catch (_: Exception) {
			return Outcome.FAILED
		}
		if (created == null) {
			return Outcome.FAILED
		}
		val document = created
		return try {
			context.contentResolver.openOutputStream(document.uri)?.use {
				it.write(bytes)
				Outcome.SAVED
			} ?: Outcome.FAILED
		} catch (_: SecurityException) {
			Outcome.NO_PERMISSION
		} catch (_: Exception) {
			Outcome.FAILED
		}
	}

	private fun uniqueName(tree: DocumentFile, name: String): String {
		if (tree.findFile(name) == null) {
			return name
		}
		val base = name.substringBeforeLast('.', name)
		val extension = if (name.contains('.')) {
			".${name.substringAfterLast('.')}"
		} else {
			""
		}
		for (index in 2..999) {
			val candidate = "$base ($index)$extension"
			if (tree.findFile(candidate) == null) {
				return candidate
			}
		}
		return name
	}

	private fun labelOf(context: Context, uri: Uri): String =
		DocumentFile.fromTreeUri(context, uri)?.name
			?: uri.lastPathSegment
			?: uri.toString()

	private fun hasPermission(context: Context, uri: Uri): Boolean =
		context.contentResolver.persistedUriPermissions.any {
			it.uri == uri && it.isWritePermission
		}
}

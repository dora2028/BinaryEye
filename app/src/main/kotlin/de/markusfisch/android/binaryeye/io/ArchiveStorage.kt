package de.markusfisch.android.binaryeye.io

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import de.markusfisch.android.binaryeye.R
import java.io.File
import java.io.InputStream

/**
 * Backs the "exported archive" dialog. The exported files live in the
 * export paths configured under the advanced features, so this lists
 * every configured path. Files of older versions that were written to
 * the app-private directory are still listed as long as no export path
 * is configured.
 */
class ArchiveStorage(private val context: Context) {

	class Entry(
		val name: String,
		val lastModified: Long,
		private val context: Context,
		private val file: File?,
		private val doc: DocumentFile?
	) {
		fun delete(): Boolean = file?.delete() ?: doc?.delete() ?: false

		fun renameTo(newName: String): Boolean {
			file?.let {
				val target = File(it.parentFile, newName)
				if (target.exists()) {
					return false
				}
				return it.renameTo(target)
			}
			val d = doc ?: return false
			if (d.parentFile?.findFile(newName) != null) {
				return false
			}
			return d.renameTo(newName)
		}

		fun openInputStream(): InputStream? {
			file?.let {
				return try {
					it.inputStream()
				} catch (_: Exception) {
					null
				}
			}
			doc?.let {
				return try {
					context.contentResolver.openInputStream(it.uri)
				} catch (_: Exception) {
					null
				}
			}
			return null
		}
	}

	private val defaultDir: File
		get() = File(context.getExternalFilesDir(null), "exports")

	fun currentLabel(): String {
		val labels = ExportPaths.targets(context).map { it.label }
		return if (labels.isEmpty()) {
			context.getString(R.string.export_paths_none)
		} else {
			labels.joinToString(" + ")
		}
	}

	fun list(): List<Entry> {
		val entries = mutableListOf<Entry>()
		val targets = ExportPaths.targets(context)
		if (targets.isEmpty()) {
			defaultDir.listFiles { file ->
				file.isFile && file.name.endsWith(".txt")
			}?.forEach { file ->
				entries.add(
					Entry(
						file.name,
						file.lastModified(),
						context,
						file,
						null
					)
				)
			}
		} else {
			targets.forEach { target ->
				val tree = DocumentFile.fromTreeUri(context, target.uri)
					?: return@forEach
				if (!tree.exists()) {
					return@forEach
				}
				tree.listFiles().forEach { doc ->
					if (doc.isFile && doc.name?.endsWith(".txt") == true) {
						entries.add(
							Entry(
								doc.name ?: "",
								doc.lastModified(),
								context,
								null,
								doc
							)
						)
					}
				}
			}
		}
		return entries.sortedByDescending { it.lastModified }
	}
}

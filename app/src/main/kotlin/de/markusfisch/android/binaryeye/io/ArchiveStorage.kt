package de.markusfisch.android.binaryeye.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import de.markusfisch.android.binaryeye.R
import de.markusfisch.android.binaryeye.app.prefs
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Backs the "exported archive" feature. Files can either live in the
 * app-private external directory (the default) or in a user-picked
 * directory granted through the Storage Access Framework.
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

	private val treeUri: Uri?
		get() = prefs.exportDirectoryUri
			.takeIf { it.isNotEmpty() }
			?.let { Uri.parse(it) }

	private val tree: DocumentFile?
		get() = treeUri?.let { DocumentFile.fromTreeUri(context, it) }

	fun currentLabel(): String = tree?.name
		?: context.getString(R.string.default_directory)

	fun list(): List<Entry> {
		val entries = mutableListOf<Entry>()
		val t = tree
		if (t != null && t.exists()) {
			t.listFiles().forEach { doc ->
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
		} else {
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
		}
		return entries.sortedByDescending { it.lastModified }
	}

	fun write(name: String, content: String): Boolean {
		val t = tree
		if (t != null && t.exists()) {
			return try {
				if (t.findFile(name) != null) {
					false
				} else {
					val doc = t.createFile("text/plain", name)
						?: return false
					context.contentResolver.openOutputStream(doc.uri)
						?.use {
							it.write(content.toByteArray())
							true
						} ?: false
				}
			} catch (_: Exception) {
				false
			}
		}
		return try {
			defaultDir.mkdirs()
			val file = File(defaultDir, name)
			if (file.exists()) {
				false
			} else {
				FileOutputStream(file).use {
					it.write(content.toByteArray())
				}
				true
			}
		} catch (_: Exception) {
			false
		}
	}
}

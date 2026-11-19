package de.markusfisch.android.binaryeye.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.markusfisch.android.binaryeye.app.prefs
import de.markusfisch.android.binaryeye.content.DiCode
import de.markusfisch.android.binaryeye.content.ParsedContentType
import de.markusfisch.android.binaryeye.content.parseData
import de.markusfisch.android.binaryeye.content.parsedContentTypeFromName
import de.markusfisch.android.binaryeye.preference.Preferences
import de.markusfisch.android.binaryeye.zxingcpp.migrateBarcodeFormatName
import de.markusfisch.android.binaryeye.zxingcpp.oldToNewFormatNames
import de.markusfisch.android.zxingcpp.ZxingCpp.BarcodeFormat

class Database {
	private lateinit var db: SQLiteDatabase
	private lateinit var context: Context

	fun open(context: Context) {
		this.context = context.applicationContext
		db = OpenHelper(context).writableDatabase
		fillMissingContentTypes()
	}

	fun getScans(
		query: String? = null,
		groupId: Long? = null,
		excludePinned: Boolean = false,
		excludeDefault: Boolean = false,
		excludeGroupIds: Set<Long> = emptySet()
	): Cursor = db.rawQuery(
		"""SELECT
			$SCANS_ID,
			$SCANS_PINNED,
			$SCANS_DATETIME,
			$SCANS_NAME,
			$SCANS_TEXT,
			$SCANS_RAW,
			$SCANS_CONTENT_TYPE,
			$SCANS_FORMAT,
			$SCANS_GROUP_ID
			FROM $SCANS
			${getWhereClause(
				query,
				groupId,
				excludePinned = excludePinned,
				excludeDefault = excludeDefault,
				excludeGroupIds = excludeGroupIds
			)}
			ORDER BY $SCANS_PINNED DESC, $SCANS_DATETIME DESC
		""".trimMargin(), getWhereArguments(
			query,
			groupId,
			excludePinned,
			excludeDefault,
			excludeGroupIds
		)
	)

	fun getScansDetailed(
		query: String? = null,
		groupId: Long? = null
	): Cursor = db.rawQuery(
		"""SELECT
			$SCANS_ID,
			$SCANS_PINNED,
			$SCANS_DATETIME,
			$SCANS_NAME,
			$SCANS_TEXT,
			$SCANS_RAW,
			$SCANS_FORMAT,
			$SCANS_ERROR_CORRECTION_LEVEL,
			$SCANS_VERSION,
			$SCANS_SEQUENCE_SIZE,
			$SCANS_SEQUENCE_INDEX,
			$SCANS_SEQUENCE_ID,
			$SCANS_GTIN_COUNTRY,
			$SCANS_GTIN_ADD_ON,
			$SCANS_GTIN_PRICE,
			$SCANS_GTIN_ISSUE_NUMBER,
			$SCANS_CONTENT_TYPE
			FROM $SCANS
			${getWhereClause(query, groupId)}
			ORDER BY $SCANS_PINNED DESC, $SCANS_DATETIME DESC
		""".trimMargin(), getWhereArguments(query, groupId)
	)

	private fun getWhereClause(
		query: String?,
		groupId: Long?,
		prefix: String = "WHERE",
		excludePinned: Boolean = false,
		excludeDefault: Boolean = false,
		excludeGroupIds: Set<Long> = emptySet()
	): String {
		val conditions = mutableListOf<String>()
		if (query?.isNotEmpty() == true) {
			conditions.add(
				"($SCANS_TEXT LIKE ? OR $SCANS_NAME LIKE ? OR $SCANS_FORMAT LIKE ?)"
			)
		}
		when {
			groupId == null -> Unit
			groupId <= 0L ->
				conditions.add("($SCANS_GROUP_ID IS NULL OR $SCANS_GROUP_ID = 0)")
			else -> conditions.add("$SCANS_GROUP_ID = ?")
		}
		if (excludePinned) {
			conditions.add("$SCANS_PINNED = 0")
		}
		if (excludeDefault) {
			conditions.add(
				"($SCANS_GROUP_ID IS NOT NULL AND $SCANS_GROUP_ID != 0)"
			)
		}
		if (excludeGroupIds.isNotEmpty()) {
			conditions.add(
				"($SCANS_GROUP_ID IS NULL OR $SCANS_GROUP_ID NOT IN (${
					excludeGroupIds.joinToString(",")
				}))"
			)
		}
		return if (conditions.isEmpty()) {
			""
		} else {
			"$prefix ${conditions.joinToString(" AND ")}"
		}
	}

	private fun getWhereArguments(
		query: String?,
		groupId: Long?,
		excludePinned: Boolean = false,
		excludeDefault: Boolean = false,
		excludeGroupIds: Set<Long> = emptySet()
	): Array<String>? {
		val args = mutableListOf<String>()
		if (query?.isNotEmpty() == true) {
			val instr = "%$query%"
			args.add(instr)
			args.add(instr)
			args.add(instr)
		}
		if (groupId != null && groupId > 0L) {
			args.add("$groupId")
		}
		return if (args.isEmpty()) null else args.toTypedArray()
	}

	fun getScansDetailed(ids: LongArray): Cursor = db.rawQuery(
		"""SELECT
			$SCANS_ID,
			$SCANS_PINNED,
			$SCANS_DATETIME,
			$SCANS_NAME,
			$SCANS_TEXT,
			$SCANS_RAW,
			$SCANS_FORMAT,
			$SCANS_ERROR_CORRECTION_LEVEL,
			$SCANS_VERSION,
			$SCANS_SEQUENCE_SIZE,
			$SCANS_SEQUENCE_INDEX,
			$SCANS_SEQUENCE_ID,
			$SCANS_GTIN_COUNTRY,
			$SCANS_GTIN_ADD_ON,
			$SCANS_GTIN_PRICE,
			$SCANS_GTIN_ISSUE_NUMBER,
			$SCANS_CONTENT_TYPE
			FROM $SCANS
			WHERE $SCANS_ID IN (${ids.joinToString(",")})
			ORDER BY $SCANS_PINNED DESC, $SCANS_DATETIME DESC
		""".trimMargin(), null
	)

	fun countScans(): Int = db.rawQuery(
		"SELECT COUNT(*) FROM $SCANS",
		null
	).use {
		if (it.moveToFirst()) it.getInt(0) else 0
	}

	/**
	 * All distinct DI codes (the 14 digits after a leading "01") that
	 * are in the history, most recent first.
	 */
	fun getDiCodes(): List<String> = db.rawQuery(
		"""SELECT
			substr($SCANS_TEXT, 3, ${DiCode.LENGTH}) AS di_code,
			MAX($SCANS_ID)
			FROM $SCANS
			WHERE $SCANS_TEXT LIKE '01%'
				AND length($SCANS_TEXT) >= ${2 + DiCode.LENGTH}
			GROUP BY di_code
			ORDER BY MAX($SCANS_ID) DESC
		""".trimMargin(), null
	).use {
		val codes = mutableListOf<String>()
		while (it.moveToNext()) {
			val code = it.getString(0)
			if (!code.isNullOrEmpty()) {
				codes.add(code)
			}
		}
		codes
	}

	/**
	 * The DI code of the most recent scan that has one, or null.
	 * When [ids] is given, only those scans are considered.
	 */
	fun getLastDiCode(ids: Collection<Long>? = null): String? {
		val idFilter = if (ids.isNullOrEmpty()) {
			""
		} else {
			"AND $SCANS_ID IN (${ids.joinToString(",")})"
		}
		return db.rawQuery(
			"""SELECT $SCANS_TEXT
				FROM $SCANS
				WHERE $SCANS_TEXT LIKE '01%'
					AND length($SCANS_TEXT) >= ${2 + DiCode.LENGTH}
					$idFilter
				ORDER BY $SCANS_ID DESC
				LIMIT 1
			""".trimMargin(), null
		).use {
			if (it.moveToFirst()) {
				DiCode.of(it.getString(0) ?: "")
			} else {
				null
			}
		}
	}

	/**
	 * All non-empty contents in chronological order, optionally
	 * limited to scans that are still in the default group.
	 */
	fun getScanContents(onlyDefaultGroup: Boolean = false): List<String> {
		val where = if (onlyDefaultGroup) {
			"WHERE $SCANS_GROUP_ID IS NULL OR $SCANS_GROUP_ID = 0"
		} else {
			""
		}
		return db.rawQuery(
			"""SELECT $SCANS_TEXT
				FROM $SCANS
				$where
				ORDER BY $SCANS_ID ASC
			""".trimMargin(), null
		).use {
			val contents = mutableListOf<String>()
			while (it.moveToNext()) {
				val content = it.getString(0)
				if (!content.isNullOrEmpty()) {
					contents.add(content)
				}
			}
			contents
		}
	}

	fun getScan(id: Long): Scan? = db.rawQuery(
		"""SELECT
			$SCANS_ID,
			$SCANS_PINNED,
			$SCANS_DATETIME,
			$SCANS_NAME,
			$SCANS_TEXT,
			$SCANS_RAW,
			$SCANS_FORMAT,
			$SCANS_ERROR_CORRECTION_LEVEL,
			$SCANS_VERSION,
			$SCANS_DATA_MASK,
			$SCANS_SYMBOL_WIDTH,
			$SCANS_SYMBOL_HEIGHT,
			$SCANS_SYMBOL_DATA,
			$SCANS_SEQUENCE_SIZE,
			$SCANS_SEQUENCE_INDEX,
			$SCANS_SEQUENCE_ID,
			$SCANS_GTIN_COUNTRY,
			$SCANS_GTIN_ADD_ON,
			$SCANS_GTIN_PRICE,
			$SCANS_GTIN_ISSUE_NUMBER,
			$SCANS_CONTENT_TYPE
			FROM $SCANS
			WHERE $SCANS_ID = ?
		""".trimMargin(), arrayOf("$id")
	).use {
		if (it.moveToFirst()) {
			Scan(
				it.getString(SCANS_TEXT),
				it.getBlob(SCANS_RAW),
				BarcodeFormat.valueOf(
					it.getString(SCANS_FORMAT).migrateBarcodeFormatName()
				),
				it.getString(SCANS_ERROR_CORRECTION_LEVEL),
				it.getString(SCANS_VERSION),
				it.getInt(SCANS_DATA_MASK),
				it.getBitMatrix(
					SCANS_SYMBOL_WIDTH,
					SCANS_SYMBOL_HEIGHT,
					SCANS_SYMBOL_DATA
				),
				it.getInt(SCANS_SEQUENCE_SIZE),
				it.getInt(SCANS_SEQUENCE_INDEX),
				it.getString(SCANS_SEQUENCE_ID),
				it.getString(SCANS_GTIN_COUNTRY),
				it.getString(SCANS_GTIN_ADD_ON),
				it.getString(SCANS_GTIN_PRICE),
				it.getString(SCANS_GTIN_ISSUE_NUMBER),
				it.getString(SCANS_DATETIME),
				it.getLong(SCANS_ID),
				it.getString(SCANS_NAME),
				it.getInt(SCANS_PINNED) != 0,
				parsedContentTypeFromName(it.getString(SCANS_CONTENT_TYPE))
			)
		} else {
			null
		}
	}

	fun insertScan(scan: Scan): Long {
		when (prefs.ignoreDuplicates()) {
			Preferences.Companion.IgnoreDuplicates.Consecutive -> {
				val id = getIdOfLastScan(
					scan.text,
					scan.raw,
					scan.format
				)
				if (id > 0L) {
					return id
				}
			}

			Preferences.Companion.IgnoreDuplicates.Any -> {
				val id = getIdOfScanByContent(scan.text, scan.format)
				if (id > 0L) {
					return id
				}
			}

			// Only consider scans that are still in the default group,
			// so codes already filed into a custom group don't block
			// saving the same code again.
			Preferences.Companion.IgnoreDuplicates.AnyInDefaultGroup -> {
				val id = getIdOfScanByContentInDefaultGroup(
					scan.text,
					scan.format
				)
				if (id > 0L) {
					return id
				}
			}

			else -> Unit
		}
		return db.insert(
			SCANS,
			null,
			ContentValues().apply {
				put(SCANS_DATETIME, scan.dateTime)
				put(SCANS_TEXT, scan.text)
				if (scan.raw != null) {
					put(SCANS_RAW, scan.raw)
				}
				put(SCANS_FORMAT, scan.format.name)
				scan.errorCorrectionLevel?.let {
					put(SCANS_ERROR_CORRECTION_LEVEL, it)
				}
				put(SCANS_VERSION, scan.version)
				put(SCANS_DATA_MASK, scan.dataMask)
				if (scan.symbol != null) {
					put(SCANS_SYMBOL_WIDTH, scan.symbol.width)
					put(SCANS_SYMBOL_HEIGHT, scan.symbol.height)
					put(SCANS_SYMBOL_DATA, scan.symbol.data)
				}
				put(SCANS_SEQUENCE_SIZE, scan.sequenceSize)
				put(SCANS_SEQUENCE_INDEX, scan.sequenceIndex)
				put(SCANS_SEQUENCE_ID, scan.sequenceId)
				scan.country?.let { put(SCANS_GTIN_COUNTRY, it) }
				scan.addOn?.let { put(SCANS_GTIN_ADD_ON, it) }
				scan.price?.let { put(SCANS_GTIN_PRICE, it) }
				scan.issueNumber?.let { put(SCANS_GTIN_ISSUE_NUMBER, it) }
				put(SCANS_PINNED, if (scan.pinned) 1 else 0)
				put(
					SCANS_CONTENT_TYPE,
					getContentType(scan.text, scan.raw, scan.format.name).name
				)
			}
		)
	}

	private fun getIdOfLastScan(
		content: String,
		raw: ByteArray?,
		format: BarcodeFormat
	): Long = db.rawQuery(
		"""SELECT
				$SCANS_ID,
				$SCANS_TEXT,
				$SCANS_RAW,
				$SCANS_FORMAT
				FROM $SCANS
				ORDER BY $SCANS_ID DESC
				LIMIT 1
			""".trimMargin(), null
	).use {
		if (it.count > 0 &&
			it.moveToFirst() &&
			it.getString(SCANS_TEXT) == content &&
			(raw == null || it.getBlob(SCANS_RAW)
				?.contentEquals(raw) == true) &&
			it.getString(SCANS_FORMAT) == format.name
		) {
			it.getLong(SCANS_ID)
		} else {
			0L
		}
	}

	private fun getIdOfScanByContent(
		content: String,
		format: BarcodeFormat
	): Long = db.rawQuery(
		"""SELECT
				$SCANS_ID
				FROM $SCANS
				WHERE $SCANS_TEXT = ?
					AND $SCANS_FORMAT = ?
				LIMIT 1
			""".trimMargin(), arrayOf(content, format.name)
	).use {
		if (it.moveToFirst()) {
			it.getLong(SCANS_ID)
		} else {
			0L
		}
	}

	private fun getIdOfScanByContentInDefaultGroup(
		content: String,
		format: BarcodeFormat
	): Long = db.rawQuery(
		"""SELECT
				$SCANS_ID
				FROM $SCANS
				WHERE $SCANS_TEXT = ?
					AND $SCANS_FORMAT = ?
					AND ($SCANS_GROUP_ID IS NULL
						OR $SCANS_GROUP_ID = 0)
				LIMIT 1
			""".trimMargin(), arrayOf(content, format.name)
	).use {
		if (it.moveToFirst()) {
			it.getLong(SCANS_ID)
		} else {
			0L
		}
	}

	fun removeScan(id: Long) {
		db.delete(SCANS, "$SCANS_ID = ?", arrayOf("$id"))
	}

	fun removeScans(query: String? = null, groupId: Long? = null) {
		db.delete(
			SCANS,
			getWhereClause(query, groupId, ""),
			getWhereArguments(query, groupId)
		)
	}

	fun renameScan(id: Long, name: String) {
		val cv = ContentValues()
		cv.put(SCANS_NAME, name)
		db.update(SCANS, cv, "$SCANS_ID = ?", arrayOf("$id"))
	}

	fun setPinned(id: Long, pinned: Boolean) {
		val cv = ContentValues()
		cv.put(SCANS_PINNED, if (pinned) 1 else 0)
		db.update(SCANS, cv, "$SCANS_ID = ?", arrayOf("$id"))
	}

	fun getGroups(): Cursor = db.rawQuery(
		"""SELECT
			$GROUPS_ID,
			$GROUPS_NAME,
			$GROUPS_COLOR,
			$GROUPS_CREATED,
			$GROUPS_PINNED
			FROM $GROUPS
			ORDER BY $GROUPS_NAME COLLATE NOCASE
		""".trimMargin(), null
	)

	fun insertGroup(name: String, color: Int): Long = try {
		db.insertOrThrow(
			GROUPS,
			null,
			ContentValues().apply {
				put(GROUPS_NAME, name)
				put(GROUPS_COLOR, color)
				put(GROUPS_CREATED, System.currentTimeMillis())
			}
		)
	} catch (_: SQLiteConstraintException) {
		-1L
	}

	fun setGroupPinned(id: Long, pinned: Boolean) {
		db.update(
			GROUPS,
			ContentValues().apply {
				put(GROUPS_PINNED, if (pinned) 1 else 0)
			},
			"$GROUPS_ID = ?",
			arrayOf("$id")
		)
	}

	fun renameGroup(id: Long, name: String): Boolean = try {
		db.update(
			GROUPS,
			ContentValues().apply {
				put(GROUPS_NAME, name)
			},
			"$GROUPS_ID = ?",
			arrayOf("$id")
		) > 0
	} catch (_: SQLiteConstraintException) {
		false
	}

	fun removeGroup(id: Long) {
		db.update(
			SCANS,
			ContentValues().apply {
				putNull(SCANS_GROUP_ID)
			},
			"$SCANS_GROUP_ID = ?",
			arrayOf("$id")
		)
		db.delete(GROUPS, "$GROUPS_ID = ?", arrayOf("$id"))
	}

	fun setScanGroup(scanId: Long, groupId: Long?) {
		val cv = ContentValues()
		if (groupId == null || groupId <= 0L) {
			cv.putNull(SCANS_GROUP_ID)
		} else {
			cv.put(SCANS_GROUP_ID, groupId)
		}
		db.update(SCANS, cv, "$SCANS_ID = ?", arrayOf("$scanId"))
	}

	private fun fillMissingContentTypes() {
		Thread {
			db.rawQuery(
				"""SELECT
					$SCANS_ID,
					$SCANS_TEXT,
					$SCANS_RAW,
					$SCANS_FORMAT
					FROM $SCANS
					WHERE $SCANS_CONTENT_TYPE IS NULL
				""".trimMargin(),
				null
			).use {
				while (it.moveToNext()) {
					val id = it.getLong(SCANS_ID)
					val type = getContentType(
						it.getString(SCANS_TEXT),
						it.getBlob(SCANS_RAW),
						it.getString(SCANS_FORMAT)
					)
					db.update(
						SCANS,
						ContentValues().apply {
							put(SCANS_CONTENT_TYPE, type.name)
						},
						"$SCANS_ID = ?",
						arrayOf("$id")
					)
				}
			}
		}.start()
	}

	private fun getContentType(
		text: String,
		raw: ByteArray?,
		format: String
	) = parseData(
		context,
		text,
		raw ?: text.toByteArray(),
		format
	).type

	private class OpenHelper(context: Context) :
		SQLiteOpenHelper(context, FILE_NAME, null, 13) {
		override fun onCreate(db: SQLiteDatabase) {
			db.createScans()
		}

		override fun onUpgrade(
			db: SQLiteDatabase,
			oldVersion: Int,
			newVersion: Int
		) {
			if (oldVersion < 2) {
				db.addRawColumn()
			}
			if (oldVersion < 3) {
				db.addMetaDataColumns()
			}
			if (oldVersion < 4) {
				db.addNameColumn()
			}
			if (oldVersion < 5) {
				db.migrateToZxingCpp()
			}
			if (oldVersion < 6) {
				db.migrateToVersionString()
			}
			if (oldVersion < 7) {
				db.addDataMaskColumn()
			}
			if (oldVersion < 8) {
				db.addSymbolColumns()
			}
			if (oldVersion < 9) {
				db.migrateToNativeFormatNames()
			}
			if (oldVersion < 10) {
				db.addPinnedColumn()
			}
			if (oldVersion < 11) {
				db.addContentTypeColumn()
			}
			if (oldVersion < 12) {
				db.addGroupColumn()
			}
			if (oldVersion < 13) {
				db.addGroupSortColumns()
			}
		}
	}

	companion object {
		const val FILE_NAME = "history.db"
		const val SCANS = "scans"
		const val SCANS_ID = "_id"
		const val SCANS_DATETIME = "_datetime"
		const val SCANS_NAME = "name"
		const val SCANS_TEXT = "content"
		const val SCANS_RAW = "raw"
		const val SCANS_FORMAT = "format"
		const val SCANS_ERROR_CORRECTION_LEVEL = "error_correction_level"
		const val SCANS_VERSION_NUMBER = "version_number"
		const val SCANS_VERSION = "version"
		const val SCANS_DATA_MASK = "data_mask"
		const val SCANS_SYMBOL_WIDTH = "symbol_width"
		const val SCANS_SYMBOL_HEIGHT = "symbol_height"
		const val SCANS_SYMBOL_DATA = "symbol_data"
		const val SCANS_ISSUE_NUMBER = "issue_number"
		const val SCANS_ORIENTATION = "orientation"
		const val SCANS_OTHER_META_DATA = "other_meta_data"
		const val SCANS_PDF417_EXTRA_METADATA = "pdf417_extra_metadata"
		const val SCANS_POSSIBLE_COUNTRY = "possible_country"
		const val SCANS_SUGGESTED_PRICE = "suggested_price"
		const val SCANS_UPC_EAN_EXTENSION = "upc_ean_extension"
		const val SCANS_SEQUENCE_SIZE = "sequence_size"
		const val SCANS_SEQUENCE_INDEX = "sequence_index"
		const val SCANS_SEQUENCE_ID = "sequence_id"
		const val SCANS_GTIN_COUNTRY = "gtin_country"
		const val SCANS_GTIN_ADD_ON = "gtin_add_on"
		const val SCANS_GTIN_PRICE = "gtin_price"
		const val SCANS_GTIN_ISSUE_NUMBER = "gtin_issue_number"
		const val SCANS_PINNED = "pinned"
		const val SCANS_CONTENT_TYPE = "content_type"
		const val SCANS_GROUP_ID = "group_id"
		const val GROUPS = "groups"
		const val GROUPS_ID = "_id"
		const val GROUPS_NAME = "name"
		const val GROUPS_COLOR = "color"
		const val GROUPS_CREATED = "created"
		const val GROUPS_PINNED = "pinned"

		private fun SQLiteDatabase.createScans() {
			execSQL("DROP TABLE IF EXISTS $SCANS".trimMargin())
			execSQL(
				"""CREATE TABLE $SCANS (
					$SCANS_ID INTEGER PRIMARY KEY AUTOINCREMENT,
					$SCANS_DATETIME DATETIME NOT NULL,
					$SCANS_NAME TEXT,
					$SCANS_TEXT TEXT NOT NULL,
					$SCANS_RAW BLOB,
					$SCANS_FORMAT TEXT NOT NULL,
					$SCANS_ERROR_CORRECTION_LEVEL TEXT,
					$SCANS_VERSION TEXT,
					$SCANS_DATA_MASK INTEGER,
					$SCANS_SYMBOL_WIDTH INTEGER,
					$SCANS_SYMBOL_HEIGHT INTEGER,
					$SCANS_SYMBOL_DATA BLOB,
					$SCANS_SEQUENCE_SIZE INTEGER,
					$SCANS_SEQUENCE_INDEX INTEGER,
					$SCANS_SEQUENCE_ID TEXT,
					$SCANS_GTIN_COUNTRY TEXT,
					$SCANS_GTIN_ADD_ON TEXT,
					$SCANS_GTIN_PRICE TEXT,
					$SCANS_GTIN_ISSUE_NUMBER TEXT,
					$SCANS_PINNED INTEGER NOT NULL DEFAULT 0,
					$SCANS_CONTENT_TYPE TEXT NOT NULL DEFAULT '${ParsedContentType.UNKNOWN.name}',
					$SCANS_GROUP_ID INTEGER
				)""".trimMargin()
			)
			createGroups()
		}

		private fun SQLiteDatabase.addRawColumn() {
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_RAW BLOB".trimMargin())
		}

		private fun SQLiteDatabase.addMetaDataColumns() {
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_ERROR_CORRECTION_LEVEL TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_ISSUE_NUMBER INT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_ORIENTATION INT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_OTHER_META_DATA TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_PDF417_EXTRA_METADATA TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_POSSIBLE_COUNTRY TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_SUGGESTED_PRICE TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_UPC_EAN_EXTENSION TEXT".trimMargin())
		}

		private fun SQLiteDatabase.addNameColumn() {
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_NAME TEXT")
		}

		private fun SQLiteDatabase.migrateToZxingCpp() {
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_VERSION_NUMBER INTEGER".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_SEQUENCE_SIZE INTEGER".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_SEQUENCE_INDEX INTEGER".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_SEQUENCE_ID TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_GTIN_COUNTRY TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_GTIN_ADD_ON TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_GTIN_PRICE TEXT".trimMargin())
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_GTIN_ISSUE_NUMBER TEXT".trimMargin())
			execSQL("UPDATE $SCANS SET $SCANS_SEQUENCE_SIZE = -1")
			execSQL("UPDATE $SCANS SET $SCANS_SEQUENCE_INDEX = -1")
			execSQL("UPDATE $SCANS SET $SCANS_GTIN_COUNTRY = $SCANS_POSSIBLE_COUNTRY")
			execSQL("UPDATE $SCANS SET $SCANS_GTIN_ADD_ON = $SCANS_UPC_EAN_EXTENSION")
			execSQL("UPDATE $SCANS SET $SCANS_GTIN_PRICE = $SCANS_SUGGESTED_PRICE")
			execSQL("UPDATE $SCANS SET $SCANS_GTIN_ISSUE_NUMBER = $SCANS_ISSUE_NUMBER")
		}

		private fun SQLiteDatabase.migrateToNativeFormatNames() {
			for ((oldName, newName) in oldToNewFormatNames) {
				execSQL(
					"UPDATE $SCANS SET $SCANS_FORMAT = ? WHERE $SCANS_FORMAT = ?",
					arrayOf(newName, oldName)
				)
			}
		}

		private fun SQLiteDatabase.migrateToVersionString() {
			execSQL("ALTER TABLE $SCANS ADD $SCANS_VERSION TEXT")
			execSQL("UPDATE $SCANS SET $SCANS_VERSION = $SCANS_VERSION_NUMBER")
		}

		private fun SQLiteDatabase.addDataMaskColumn() {
			execSQL("ALTER TABLE $SCANS ADD $SCANS_DATA_MASK INTEGER")
			execSQL("UPDATE $SCANS SET $SCANS_DATA_MASK = -1")
		}

		private fun SQLiteDatabase.addSymbolColumns() {
			execSQL("ALTER TABLE $SCANS ADD $SCANS_SYMBOL_WIDTH INTEGER")
			execSQL("ALTER TABLE $SCANS ADD $SCANS_SYMBOL_HEIGHT INTEGER")
			execSQL("ALTER TABLE $SCANS ADD $SCANS_SYMBOL_DATA BLOB")
		}

		private fun SQLiteDatabase.addPinnedColumn() {
			execSQL(
				"ALTER TABLE $SCANS ADD COLUMN $SCANS_PINNED INTEGER NOT NULL DEFAULT 0"
			)
		}

		private fun SQLiteDatabase.addContentTypeColumn() {
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_CONTENT_TYPE TEXT")
		}

		private fun SQLiteDatabase.createGroups() {
			execSQL("DROP TABLE IF EXISTS $GROUPS".trimMargin())
			execSQL(
				"""CREATE TABLE $GROUPS (
					$GROUPS_ID INTEGER PRIMARY KEY AUTOINCREMENT,
					$GROUPS_NAME TEXT NOT NULL UNIQUE,
					$GROUPS_COLOR INTEGER NOT NULL DEFAULT 0,
					$GROUPS_CREATED INTEGER NOT NULL DEFAULT 0,
					$GROUPS_PINNED INTEGER NOT NULL DEFAULT 0
				)""".trimMargin()
			)
		}

		private fun SQLiteDatabase.addGroupColumn() {
			createGroups()
			execSQL("ALTER TABLE $SCANS ADD COLUMN $SCANS_GROUP_ID INTEGER")
		}

		private fun SQLiteDatabase.addGroupSortColumns() {
			// Idempotent: createGroups() already creates the full table on
			// fresh installs and on the v11 -> v12 step, so only add the
			// columns when upgrading from an existing v12 database.
			val existing = rawQuery(
				"PRAGMA table_info($GROUPS)", null
			).use { c ->
				buildSet {
					while (c.moveToNext()) {
						add(c.getString(c.getColumnIndex("name")))
					}
				}
			}
			if (GROUPS_CREATED !in existing) {
				execSQL(
					"ALTER TABLE $GROUPS ADD COLUMN $GROUPS_CREATED INTEGER NOT NULL DEFAULT 0"
				)
			}
			if (GROUPS_PINNED !in existing) {
				execSQL(
					"ALTER TABLE $GROUPS ADD COLUMN $GROUPS_PINNED INTEGER NOT NULL DEFAULT 0"
				)
			}
		}
	}
}

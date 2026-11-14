package de.markusfisch.android.binaryeye.database

data class ScanGroup(
	val id: Long,
	val name: String,
	val color: Int,
	val created: Long = 0L,
	val pinned: Boolean = false
)

// Preset palette used to tint group markers. New groups cycle through it.
val GROUP_COLORS = intArrayOf(
	0xFFE53935.toInt(), // red
	0xFFD81B60.toInt(), // pink
	0xFF8E24AA.toInt(), // purple
	0xFF5E35B1.toInt(), // deep purple
	0xFF3949AB.toInt(), // indigo
	0xFF1E88E5.toInt(), // blue
	0xFF00897B.toInt(), // teal
	0xFF43A047.toInt(), // green
	0xFFFB8C00.toInt(), // orange
	0xFF6D4C41.toInt()  // brown
)

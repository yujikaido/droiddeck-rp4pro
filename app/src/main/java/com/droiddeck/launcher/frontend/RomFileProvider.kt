package com.droiddeck.launcher.frontend

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * ROM files as content URIs for Android emulators that take one ({file.uri}; AndroidLaunch). Besides FileProvider's
 * name and size, a query also answers `_data`, the file's own path: My Boy! reads it (the third column) to open the
 * ROM itself, and with only two columns it reports "file not found" and quits.
 */
class RomFileProvider : FileProvider() {
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        // rom_paths.xml maps the whole filesystem under "root": content://<authority>/root/<absolute path>
        val file = uri.path?.removePrefix("/root")?.let(::File)
        if (file == null || !file.isFile) return super.query(uri, projection, selection, selectionArgs, sortOrder)
        val cols = projection?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DATA)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { c ->
                when (c) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    DATA -> file.absolutePath
                    else -> null
                }
            })
        }
    }

    private companion object {
        const val DATA = "_data"
    }
}

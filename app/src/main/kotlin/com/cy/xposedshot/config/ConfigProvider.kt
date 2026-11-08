package com.cy.xposedshot.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

/**
 * 只读配置通道：system_server 用 system context 直接 query。
 *
 * 只放行 UID 1000（system）与自身；其他调用方返回 null，避免任何 App 读到名单。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx: Context = context ?: return null
        if (uri != ConfigStore.URI) return null
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != Process.myUid()) return null
        return MatrixCursor(arrayOf("json"), 1).apply {
            addRow(arrayOf<Any>(ConfigStore.json(ctx)))
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri == ConfigStore.URI) "vnd.android.cursor.item/xposedshot-config" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

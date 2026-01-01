package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.BackupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

object SafAutoSyncHelper {
    private const val PREF_SYNC_URI = "saf_auto_sync_uri"
    private const val SYNC_FILE_NAME = "libretube_autosync.json"

    fun getSyncUri(context: Context): Uri? {
        val uriString = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_SYNC_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    fun setSyncUri(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        // 기존 권한 해제 (선택 사항)
        getSyncUri(context)?.let { oldUri ->
            try {
                resolver.releasePersistableUriPermission(oldUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { /* 무시 */ }
        }

        // 새 권한 영구 획득
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_SYNC_URI, uri.toString())
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportData(context: Context) = withContext(Dispatchers.IO) {
        val treeUri = getSyncUri(context) ?: return@withContext
        val resolver = context.contentResolver
        val treeFile = DocumentFile.fromTreeUri(context, treeUri)

        if (treeFile == null || !treeFile.canWrite()) {
            Log.e(TAG(), "Sync folder not accessible")
            return@withContext
        }

        // 기존 파일 찾기 또는 생성
        val file = treeFile.findFile(SYNC_FILE_NAME) ?: treeFile.createFile("application/json", SYNC_FILE_NAME)

        if (file == null) {
            Log.e(TAG(), "Could not create sync file")
            return@withContext
        }

        // DB에서 데이터 가져오기 (WatchHistory, WatchPosition만)
        val watchHistory = Database.watchHistoryDao().getAll()
        val watchPositions = Database.watchPositionDao().getAll()

        val backupData = BackupFile(
            watchHistory = watchHistory,
            watchPositions = watchPositions,
            // 나머지는 기본값(empty) 사용하여 덮어쓰기 방지하거나 최소화
            searchHistory = null,
            customInstances = null,
            playlistBookmarks = null,
            preferences = null,
            groups = null,
            subscriptions = null,
            localPlaylists = null,
            playlists = null
        )

        try {
            resolver.openOutputStream(file.uri)?.use { outputStream ->
                JsonHelper.json.encodeToStream(backupData, outputStream)
            }
            Log.d(TAG(), "Auto-sync export successful")
        } catch (e: Exception) {
            Log.e(TAG(), "Error exporting auto-sync data", e)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importData(context: Context) = withContext(Dispatchers.IO) {
        val treeUri = getSyncUri(context) ?: return@withContext
        val treeFile = DocumentFile.fromTreeUri(context, treeUri)
        val file = treeFile?.findFile(SYNC_FILE_NAME)

        if (file == null || !file.exists()) {
            return@withContext // 파일이 없으면 스킵
        }

        try {
            val backupData = context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                JsonHelper.json.decodeFromStream<BackupFile>(inputStream)
            } ?: return@withContext

            // 데이터 병합 (기존 데이터 유지 + 새 데이터 추가/갱신)
            if (!backupData.watchHistory.isNullOrEmpty()) {
                Database.watchHistoryDao().insertAllSync(backupData.watchHistory!!)
            }
            if (!backupData.watchPositions.isNullOrEmpty()) {
                Database.watchPositionDao().insertAllSync(backupData.watchPositions!!)
            }

            withContext(Dispatchers.Main) {
                // 필요 시 토스트 출력
                // context.toastFromMainDispatcher(R.string.backup_restore_success)
            }
            Log.d(TAG(), "Auto-sync import successful")

        } catch (e: Exception) {
            Log.e(TAG(), "Error importing auto-sync data", e)
        }
    }
}
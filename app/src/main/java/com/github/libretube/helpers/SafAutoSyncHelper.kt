package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.github.libretube.api.JsonHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.obj.BackupFile
import com.github.libretube.db.obj.LocalPlaylistWithVideos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

object SafAutoSyncHelper {
    private const val TAG = "SafAutoSyncHelper"
    private const val PREF_SYNC_URI = "saf_auto_sync_uri"
    private const val SYNC_FILE_NAME = "libretube_autosync.json"

    fun getSyncUri(context: Context): Uri? {
        val uriString = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_SYNC_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    fun setSyncUri(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        getSyncUri(context)?.let { oldUri ->
            try {
                resolver.releasePersistableUriPermission(oldUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { /* Ignore */ }
        }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_SYNC_URI, uri.toString())
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportData(context: Context) = withContext(Dispatchers.IO) {
        val treeUri = getSyncUri(context) ?: return@withContext
        val treeFile = DocumentFile.fromTreeUri(context, treeUri)
        if (treeFile == null || !treeFile.canWrite()) return@withContext

        val file = treeFile.findFile(SYNC_FILE_NAME) ?: treeFile.createFile("application/json", SYNC_FILE_NAME)
        if (file == null) return@withContext

        try {
            var existingBackup: BackupFile? = null
            try {
                if (file.length() > 0) {
                    context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                        existingBackup = JsonHelper.json.decodeFromStream<BackupFile>(inputStream)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read existing file, starting fresh export.", e)
            }

            // Fetch local data
            val localHistory = Database.watchHistoryDao().getAll()
            val localPositions = Database.watchPositionDao().getAll()
            val localBookmarks = Database.playlistBookmarkDao().getAll()
            val localSearch = Database.searchHistoryDao().getAll()
            val localGroups = Database.subscriptionGroupsDao().getAll()
            val localSubs = Database.localSubscriptionDao().getAll()
            // getAll() returns List<LocalPlaylistWithVideos> in LocalPlaylistsDao
            val localPlaylists = Database.localPlaylistsDao().getAll()

            // Merge Logic: Using correct Primary Keys from provided files

            // WatchHistoryItem: PrimaryKey is 'videoId'
            val mergedHistory = (existingBackup?.watchHistory.orEmpty() + localHistory)
                .distinctBy { it.videoId }

            // WatchPosition: PrimaryKey is 'videoId'
            val mergedPositions = (existingBackup?.watchPositions.orEmpty() + localPositions)
                .distinctBy { it.videoId }

            // PlaylistBookmark: PrimaryKey is 'playlistId'
            val mergedBookmarks = (existingBackup?.playlistBookmarks.orEmpty() + localBookmarks)
                .distinctBy { it.playlistId }

            // SearchHistoryItem: assuming 'query' is unique (standard)
            val mergedSearch = (existingBackup?.searchHistory.orEmpty() + localSearch)
                .distinctBy { it.query }

            // SubscriptionGroup: PrimaryKey is 'name' (not id)
            val mergedGroups = (existingBackup?.groups.orEmpty() + localGroups)
                .distinctBy { it.name }

            // LocalSubscription: PrimaryKey is 'channelId' (not url)
            val mergedSubs = (existingBackup?.subscriptions.orEmpty() + localSubs)
                .distinctBy { it.channelId }

            // Local Playlists Merge (ID is Int, but we merge by Name to sync across devices)
            val allPlaylists = (existingBackup?.localPlaylists.orEmpty() + localPlaylists)
            val mergedPlaylists = allPlaylists.groupBy { it.playlist.name }.map { (_, lists) ->
                val masterPlaylist = lists.first().playlist
                // distinctBy videoId (String) to avoid duplicate videos
                val combinedVideos = lists.flatMap { it.videos }.distinctBy { it.videoId }

                LocalPlaylistWithVideos(masterPlaylist, combinedVideos)
            }

            val finalBackup = BackupFile(
                watchHistory = mergedHistory,
                watchPositions = mergedPositions,
                playlistBookmarks = mergedBookmarks,
                searchHistory = mergedSearch,
                groups = mergedGroups,
                subscriptions = mergedSubs,
                localPlaylists = mergedPlaylists,
                playlists = emptyList()
            )

            context.contentResolver.openOutputStream(file.uri, "wt")?.use { outputStream ->
                JsonHelper.json.encodeToStream(finalBackup, outputStream)
            }
            Log.d(TAG, "Safe Additive Export successful")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export data", e)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importData(context: Context) = withContext(Dispatchers.IO) {
        val treeUri = getSyncUri(context) ?: return@withContext
        val treeFile = DocumentFile.fromTreeUri(context, treeUri)
        val file = treeFile?.findFile(SYNC_FILE_NAME) ?: return@withContext

        try {
            val backupData = context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                JsonHelper.json.decodeFromStream<BackupFile>(inputStream)
            } ?: return@withContext

            // Batch Insert (Ignore/Replace handled by DAO)
            backupData.watchHistory?.let { Database.watchHistoryDao().insertAll(it) }
            backupData.watchPositions?.let { Database.watchPositionDao().insertAll(it) }
            backupData.playlistBookmarks?.let { Database.playlistBookmarkDao().insertAll(it) }
            backupData.searchHistory?.let { Database.searchHistoryDao().insertAll(it) }
            backupData.groups?.let { Database.subscriptionGroupsDao().insertAll(it) }
            backupData.subscriptions?.let { Database.localSubscriptionDao().insertAll(it) }

            // Playlist Import Logic (Int ID)
            backupData.localPlaylists?.forEach { imported ->
                val existing = Database.localPlaylistsDao().getAll()
                val match = existing.find { it.playlist.name == imported.playlist.name }

                val targetId: Int = if (match != null) {
                    match.playlist.id // Existing Int ID
                } else {
                    // Create new. Returns Long -> cast to Int
                    val newIdLong = Database.localPlaylistsDao().createPlaylist(imported.playlist.copy(id = 0))
                    newIdLong.toInt()
                }

                imported.videos.forEach { video ->
                    try {
                        val videoToInsert = video.copy(
                            id = 0, // Int, Auto-increment
                            playlistId = targetId // Int, Foreign Key
                        )
                        Database.localPlaylistsDao().addPlaylistVideo(videoToInsert)
                    } catch (e: Exception) { /* Skip duplicates */ }
                }
            }
            Log.d(TAG, "Additive Import successful")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import data", e)
        }
    }
}
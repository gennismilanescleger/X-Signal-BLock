// Tile cache with protection (SignalX 1.0.9 SafeTileCache.kt, ported; Fase 5).
//
// CAUSE (verified in the Mapsforge 0.25.0 sources): the app passed
// 150,000,000 as the FileSystemTileCache capacity thinking it was 150 MB
// on disk, but Mapsforge reads it as MAXIMUM NUMBER OF ENTRIES. Its LRU
// sizes its table at capacity/0.6 → 250,000,002 → 2^28, and saving the
// first tile makes HashMap.resize try to reserve 644 MB on a phone with
// a 256 MB heap → OutOfMemoryError on the download thread, killing the
// whole app.
//
// What this wrapper does, without touching the library:
// - Entry capacity is now 4000 (OnlineMap.CACHE_MAX_ENTRIES): the in-memory table stays in kilobytes.
// - put() swallows any error (including OutOfMemoryError): a bad tile
//   never kills the app; it reports through onTileFailure so the app can
//   pause the download and open the grid if several fail in a row.
// - Per-tile cap: a saved file over 1 MB is a bad response and is
//   deleted; its key stays blocked in memory for this session
//   (containsKey says it is there, get returns null → the tile stays
//   blank, not in a loop).
// - Disk cap: every 32 saved tiles the directory size is checked and the
//   oldest tiles are deleted until under 150 MB (FileSystemTileCache does
//   not enforce that cap alone). Deleting files from outside is safe:
//   the library's get() drops the key on its own if the file is gone.

package dev.ririxi.bunnylock.signalx

import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.map.layer.cache.FileSystemTileCache
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.queue.Job
import org.mapsforge.map.model.common.Observer
import java.io.File

class SafeTileCache(
    val raw: FileSystemTileCache,
    private val cacheDir: File,
    private val maxTileBytes: Long = OnlineMap.MAX_TILE_FILE_BYTES,
    private val maxDiskBytes: Long = OnlineMap.CACHE_MAX_BYTES.toLong(),
    private val onTileFailure: (() -> Unit)? = null,
    private val onTileStored: (() -> Unit)? = null,
) : TileCache {
    companion object {
        private const val TRIM_EVERY_PUTS = 32
    }

    private val blockedKeys = mutableSetOf<String>()
    private var putsSinceTrim = 0

    /** Forgets the blocked keys (when clearing the cache, for example). */
    fun reset() {
        synchronized(blockedKeys) { blockedKeys.clear() }
        putsSinceTrim = 0
    }

    override fun containsKey(key: Job): Boolean {
        return try {
            synchronized(blockedKeys) { if (key.key in blockedKeys) return true }
            raw.containsKey(key)
        } catch (_: Exception) {
            false
        }
    }

    override fun get(key: Job): TileBitmap? {
        return try {
            synchronized(blockedKeys) { if (key.key in blockedKeys) return null }
            raw.get(key)
        } catch (_: Exception) {
            null
        }
    }

    override fun getImmediately(key: Job): TileBitmap? = get(key)

    override fun put(
        key: Job,
        bitmap: TileBitmap,
    ) {
        try {
            raw.put(key, bitmap)
        } catch (t: Throwable) {
            // A bad tile never kills the app (SignalX 1.0.9).
            try {
                onTileFailure?.invoke()
            } catch (_: Exception) {
            }
            return
        }
        try {
            onTileStored?.invoke()
        } catch (_: Exception) {
        }
        try {
            // Per-tile cap: over 1 MB is a bad response.
            val file = File(cacheDir, key.key + ".tile")
            if (file.isFile && file.length() > maxTileBytes) {
                file.delete()
                synchronized(blockedKeys) { blockedKeys.add(key.key) }
            }
            // Disk cap, throttled: only checked every 32 tiles.
            putsSinceTrim++
            if (putsSinceTrim >= TRIM_EVERY_PUTS) {
                putsSinceTrim = 0
                trimDisk()
            }
        } catch (_: Exception) {
        }
    }

    /** Deletes the oldest tiles until under the disk cap. */
    private fun trimDisk() {
        try {
            val files =
                cacheDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".tile") }
                    .toList()
            var total = files.sumOf { it.length() }
            if (total <= maxDiskBytes) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxDiskBytes) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Exception) {
        }
    }

    override fun destroy() {
        try {
            raw.destroy()
        } catch (_: Exception) {
        }
        reset()
    }

    override fun purge() {
        try {
            raw.purge()
        } catch (_: Exception) {
        }
        reset()
    }

    override fun getCapacity(): Int {
        return try {
            raw.capacity
        } catch (_: Exception) {
            0
        }
    }

    override fun getCapacityFirstLevel(): Int = getCapacity()

    override fun setWorkingSet(workingSet: Set<Job>) {
        try {
            raw.setWorkingSet(workingSet)
        } catch (_: Exception) {
        }
    }

    override fun addObserver(observer: Observer) {
        try {
            raw.addObserver(observer)
        } catch (_: Exception) {
        }
    }

    override fun removeObserver(observer: Observer) {
        try {
            raw.removeObserver(observer)
        } catch (_: Exception) {
        }
    }
}

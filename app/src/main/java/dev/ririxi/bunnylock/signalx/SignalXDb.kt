// SignalX field records, SQLite direct (superprompt §1.1, §13; Fase 3).
//
// One database, "signalx.db", version 3 (v1 Fase 3, v2 Fase 4 tower_positions, v3 Fase 5 points). BunnyLock keeps its
// own storage (SharedPreferences "bunnylock": locks, backend, settings)
// untouched; this base holds field records only — saved measurements,
// their neighbor cells, and the observed-cells registry. One access point,
// no duplication with BunnyLock's storage. No Room, no ORM.
//
// The schema adapts SignalX 1.0.9 (DbHelper v3) instead of copying it:
//   - observations keeps eci/rsrp/rsrq/sinr/lat/lon/time/accuracy and gains
//     the fields a saved reading needs to stand on its own: rat, nci, tac,
//     pci, earfcn, band, plmn, rssi, azimuth, source (manual/auto) and
//     reading_time (when the reading was taken, as opposed to when it was
//     saved).
//   - observation_neighbors is new: the neighbors belong to the
//     measurement, so they live in their own table instead of being lost.
//   - cells is the observed-cells registry (SignalX shape) plus rssi.
//     It stays LTE-only: NR cells have no ECI to key on.
//   - tower_positions arrived with Fase 4 (Antenas): manual antenna
//     positions, keyed by eNB. The seed catalog itself is an asset, not a
//     table; only what the user places by hand lives here, so removing a
//     manual position falls back to the seed, never deletes the antenna.
//   - points arrived with Fase 5 (Orientación): the install point saved
//     from the compass (GPS + azimuth + signal at save time). Photos are
//     NOT here yet; they arrive with the camera (Fase 6), and the save
//     button never promises one.
//   - photos + points.photo_path arrived with Fase 6 (Herramientas >
//     Cámara/Datos): the field-camera registry (SignalX 1.0.9 photos
//     table shape) and the photo attached to a saved point. A point
//     keeps its photo_path; the photos row is the registry of the file.
//     Deleting a point never deletes the photo file — the photo survives
//     in Datos > Photos until it is deleted there.
// A missing value is stored as NULL, never 0.

package dev.ririxi.bunnylock.signalx

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.ririxi.bunnylock.data.Rat

/** One manual antenna position, as stored in tower_positions. */
data class TowerPosition(
    val enb: Int,
    val lat: Double,
    val lon: Double,
    val source: String,
    val time: Long,
)

/** Observed cells of one eNB, grouped from the cells table (LTE only). */
data class ObservedEnbStats(
    /** Distinct observed cells (ECIs) for this eNB. */
    val count: Int,
    /** Sectors seen (ECI & 0xFF per cell), sorted. */
    val sectors: List<Int>,
    /** Bands seen ("B3" style), distinct, in first-seen order. */
    val bands: String,
    /** Best observed position: lowest GPS accuracy, strongest RSRP on a tie. Null when no observed cell carries a position. */
    val bestLat: Double?,
    val bestLon: Double?,
)
/** One saved install point (compass "Save point", Fase 5). Fase 6: [photoPath] is the photo attached to the point, if any — the file the field camera saved (filesDir/photos), or null. */
data class SavedPoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val azimuth: Float?,
    val rsrp: Int?,
    val time: Long,
    val photoPath: String? = null,
)

/** One field photo (Fase 6): the registry of a JPEG the camera saved. SignalX 1.0.9 photos shape. */
data class PhotoRecord(
    val id: Long,
    val path: String,
    val lat: Double?,
    val lon: Double?,
    val azimuth: Float?,
    val accuracy: Float?,
    val time: Long,
)

/** One saved measurement, with its neighbor cells, for the Datos detail view (Fase 6). */
data class ObservationDetail(
    val id: Long,
    val rat: Rat,
    val eci: Long?,
    val nci: Long?,
    val tac: Int?,
    val pci: Int?,
    val earfcn: Long?,
    val band: String?,
    val plmn: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val rssi: Int?,
    val sinr: Int?,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Float?,
    val azimuth: Float?,
    val source: String,
    val time: Long,
    val readingTime: Long?,
    val neighbors: List<NeighborRow>,
)

data class NeighborRow(
    val rat: Rat,
    val eci: Long?,
    val nci: Long?,
    val pci: Int?,
    val earfcn: Long?,
    val band: String?,
    val rsrp: Int?,
    val rssi: Int?,
)

/** One observed cell, for Datos > Observed cells (Fase 6). */
data class ObservedCellRow(
    val eci: Long,
    val tac: Int?,
    val pci: Int?,
    val earfcn: Long?,
    val band: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val lat: Double?,
    val lon: Double?,
    val time: Long?,
)

/** Real counters for the Diagnóstico screen (Fase 6). */
data class DbCounts(
    val observations: Int,
    val neighbors: Int,
    val cells: Int,
    val points: Int,
    val photos: Int,
    val towerPositions: Int,
)

data class MeasurementSummary(
    val id: Long,
    val rat: Rat,
    val eci: Long?,
    val nci: Long?,
    val rsrp: Int?,
    val timeMs: Long,
    val neighborCount: Int,
)

class SignalXDb(
    context: Context,
) : SQLiteOpenHelper(context, "signalx.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rat TEXT NOT NULL, eci INTEGER, nci INTEGER, tac INTEGER,
                pci INTEGER, earfcn INTEGER, band TEXT, plmn TEXT,
                rsrp INTEGER, rsrq INTEGER, rssi INTEGER, sinr INTEGER,
                lat REAL, lon REAL, accuracy REAL, azimuth REAL,
                source TEXT NOT NULL, time INTEGER NOT NULL, reading_time INTEGER)""",
        )
        db.execSQL(
            """CREATE TABLE observation_neighbors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                observation_id INTEGER NOT NULL,
                rat TEXT NOT NULL, eci INTEGER, nci INTEGER,
                pci INTEGER, earfcn INTEGER, band TEXT,
                rsrp INTEGER, rssi INTEGER, time INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE cells (
                eci INTEGER PRIMARY KEY, tac INTEGER, pci INTEGER, earfcn INTEGER,
                band TEXT, mcc TEXT, mnc TEXT, rsrp INTEGER, rsrq INTEGER,
                rssi INTEGER, sinr INTEGER, lat REAL, lon REAL,
                time INTEGER, accuracy REAL)""",
        )
        db.execSQL(
            """CREATE TABLE tower_positions (
                enb INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,
                source TEXT NOT NULL, time INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL, lon REAL NOT NULL,
                accuracy REAL, azimuth REAL, rsrp INTEGER,
                photo_path TEXT,
                time INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL,
                lat REAL, lon REAL, azimuth REAL, accuracy REAL,
                time INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX idx_observations_time ON observations(time DESC)")
        db.execSQL("CREATE INDEX idx_neighbors_observation ON observation_neighbors(observation_id)")
        db.execSQL("CREATE INDEX idx_points_time ON points(time DESC)")
        db.execSQL("CREATE INDEX idx_photos_time ON photos(time DESC)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // v1 -> v2 (Fase 4): only adds tower_positions. Nothing is dropped,
        // nothing is rewritten: observations, neighbors and cells survive.
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS tower_positions (
                    enb INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,
                    source TEXT NOT NULL, time INTEGER NOT NULL)""",
            )
        }
        // v2 -> v3 (Fase 5): only adds points. Nothing is dropped, nothing
        // is rewritten: everything saved before survives.
        if (oldVersion < 3) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS points (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    lat REAL NOT NULL, lon REAL NOT NULL,
                    accuracy REAL, azimuth REAL, rsrp INTEGER,
                    time INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_points_time ON points(time DESC)")
        }
        // v3 -> v4 (Fase 6): only adds the photo column on points and the
        // photos registry. Nothing is dropped, nothing is rewritten:
        // every measurement, point and manual position saved before
        // survives, with photo_path NULL until a photo is attached.
        if (oldVersion < 4) {
            if (!hasColumn(db, "points", "photo_path")) {
                db.execSQL("ALTER TABLE points ADD COLUMN photo_path TEXT")
            }
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS photos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL,
                    lat REAL, lon REAL, azimuth REAL, accuracy REAL,
                    time INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_time ON photos(time DESC)")
        }
    }

    private fun hasColumn(
        db: SQLiteDatabase,
        table: String,
        column: String,
    ): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == column) return true
            }
        }
        return false
    }

    /**
     * Saves one snapshot (serving + neighbors) and upserts the serving LTE
     * cell into the observed-cells registry. One transaction, so a
     * measurement never lands half-saved. Returns the observation id, or -1.
     */
    fun insertMeasurement(snapshot: MeasurementSnapshot): Long {
        val db = writableDatabase
        var id = -1L
        db.beginTransaction()
        try {
            val serving = snapshot.serving
            val values =
                ContentValues().apply {
                    put("rat", serving.rat.name)
                    put("eci", serving.eci)
                    put("nci", serving.nci)
                    put("tac", serving.tac)
                    put("pci", serving.pci)
                    put("earfcn", serving.earfcn)
                    put("band", serving.band)
                    put("plmn", serving.plmn)
                    put("rsrp", serving.rsrp)
                    put("rsrq", serving.rsrq)
                    put("rssi", serving.rssi)
                    put("sinr", serving.sinr)
                    put("lat", snapshot.location?.latitude)
                    put("lon", snapshot.location?.longitude)
                    put("accuracy", snapshot.location?.accuracy)
                    put("azimuth", snapshot.location?.azimuth)
                    put("source", snapshot.source.name)
                    put("time", snapshot.timeMs)
                    put("reading_time", snapshot.readingAtMs)
                }
            id = db.insert("observations", null, values)
            if (id >= 0) {
                for (neighbor in snapshot.neighbors) {
                    val neighborValues =
                        ContentValues().apply {
                            put("observation_id", id)
                            put("rat", neighbor.rat.name)
                            put("eci", neighbor.eci)
                            put("nci", neighbor.nci)
                            put("pci", neighbor.pci)
                            put("earfcn", neighbor.earfcn)
                            put("band", neighbor.band)
                            put("rsrp", neighbor.rsrp)
                            put("rssi", neighbor.rssi)
                            put("time", snapshot.timeMs)
                        }
                    db.insert("observation_neighbors", null, neighborValues)
                }
                upsertObservedCell(db, snapshot)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    /** The serving LTE cell, kept as the last-seen record for that ECI. NR has no ECI to key on, so it skips this table. */
    private fun upsertObservedCell(
        db: SQLiteDatabase,
        snapshot: MeasurementSnapshot,
    ) {
        val serving = snapshot.serving
        if (serving.rat != Rat.LTE) return
        val eci = serving.eci ?: return
        val plmn = serving.plmn
        val values =
            ContentValues().apply {
                put("eci", eci)
                put("tac", serving.tac)
                put("pci", serving.pci)
                put("earfcn", serving.earfcn)
                put("band", serving.band)
                put("mcc", plmn?.take(3))
                put("mnc", plmn?.drop(3)?.takeIf { it.isNotEmpty() })
                put("rsrp", serving.rsrp)
                put("rsrq", serving.rsrq)
                put("rssi", serving.rssi)
                put("sinr", serving.sinr)
                put("lat", snapshot.location?.latitude)
                put("lon", snapshot.location?.longitude)
                put("time", snapshot.timeMs)
                put("accuracy", snapshot.location?.accuracy)
            }
        db.insertWithOnConflict("cells", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The newest saved measurement, or null when nothing has been saved yet. */
    fun lastMeasurement(): MeasurementSummary? {
        readableDatabase.query("observations", null, null, null, null, null, "time DESC", "1").use { c ->
            if (!c.moveToFirst()) return null
            val rat = Rat.entries.firstOrNull { it.name == c.getString(c.getColumnIndexOrThrow("rat")) } ?: return null
            val id = c.getLong(c.getColumnIndexOrThrow("id"))
            return MeasurementSummary(
                id = id,
                rat = rat,
                eci = c.longOrNull("eci"),
                nci = c.longOrNull("nci"),
                rsrp = c.intOrNull("rsrp"),
                timeMs = c.getLong(c.getColumnIndexOrThrow("time")),
                neighborCount = countNeighbors(id),
            )
        }
    }

    /** How many measurements have been saved (for Settings). */
    fun measurementCount(): Int =
        try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM observations", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }

    // ---- Manual antenna positions (Fase 4) ----

    /** Saves (or moves) a manual position. Replaces only the tower_positions row: the seed catalog is an asset and is never touched. */
    fun upsertTowerPosition(
        enb: Int,
        lat: Double,
        lon: Double,
    ) {
        val values =
            ContentValues().apply {
                put("enb", enb)
                put("lat", lat)
                put("lon", lon)
                put("source", "manual")
                put("time", System.currentTimeMillis())
            }
        writableDatabase.insertWithOnConflict("tower_positions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Removes a manual position. The antenna falls back to its estimated (or observed) position; the catalog row is never deleted. */
    fun deleteTowerPosition(enb: Int) {
        writableDatabase.delete("tower_positions", "enb = ?", arrayOf(enb.toString()))
    }

    fun towerPosition(enb: Int): TowerPosition? {
        readableDatabase.query("tower_positions", null, "enb = ?", arrayOf(enb.toString()), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) return null
            return TowerPosition(
                enb = c.getInt(c.getColumnIndexOrThrow("enb")),
                lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
                lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
                source = c.getString(c.getColumnIndexOrThrow("source")) ?: "manual",
                time = c.getLong(c.getColumnIndexOrThrow("time")),
            )
        }
    }

    fun listTowerPositions(): List<TowerPosition> {
        val out = ArrayList<TowerPosition>()
        readableDatabase.query("tower_positions", null, null, null, null, null, "enb ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    TowerPosition(
                        enb = c.getInt(c.getColumnIndexOrThrow("enb")),
                        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
                        lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
                        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "manual",
                        time = c.getLong(c.getColumnIndexOrThrow("time")),
                    ),
                )
            }
        }
        return out
    }

    /**
     * Observed LTE cells grouped by eNB (ECI >> 8), in ONE read of the
     * cells table (SignalX 1.0.8 rule: one read per rebuild, not one
     * query per antenna). NR cells are not in this table — it is keyed
     * by ECI — so they never appear here; they stay in observations.
     * Rows with ECI 0 or null carry no identity and are skipped.
     */
    fun observedCellsByEnb(): Map<Int, ObservedEnbStats> {
        val counts = HashMap<Int, Int>()
        val sectors = HashMap<Int, MutableSet<Int>>()
        val bands = HashMap<Int, LinkedHashSet<String>>()
        val best = HashMap<Int, ObservedCellPosition>()
        readableDatabase.query("cells", null, null, null, null, null, null).use { c ->
            val iEci = c.getColumnIndexOrThrow("eci")
            val iBand = c.getColumnIndexOrThrow("band")
            val iRsrp = c.getColumnIndexOrThrow("rsrp")
            val iLat = c.getColumnIndexOrThrow("lat")
            val iLon = c.getColumnIndexOrThrow("lon")
            val iAcc = c.getColumnIndexOrThrow("accuracy")
            while (c.moveToNext()) {
                if (c.isNull(iEci)) continue
                val eci = c.getLong(iEci)
                if (eci <= 0) continue
                val enb = (eci shr 8).toInt()
                counts[enb] = (counts[enb] ?: 0) + 1
                sectors.getOrPut(enb) { mutableSetOf() }.add((eci and 0xFF).toInt())
                c.getString(iBand)?.takeIf { it.isNotEmpty() }?.let { bands.getOrPut(enb) { linkedSetOf() }.add(it) }
                if (!c.isNull(iLat) && !c.isNull(iLon)) {
                    val candidate =
                        ObservedCellPosition(
                            lat = c.getDouble(iLat),
                            lon = c.getDouble(iLon),
                            accuracy = if (c.isNull(iAcc)) null else c.getFloat(iAcc),
                            rsrp = if (c.isNull(iRsrp)) null else c.getInt(iRsrp),
                        )
                    val current = best[enb]
                    if (current == null || candidate.isBetterThan(current)) best[enb] = candidate
                }
            }
        }
        val out = HashMap<Int, ObservedEnbStats>()
        for ((enb, count) in counts) {
            val b = best[enb]
            out[enb] =
                ObservedEnbStats(
                    count = count,
                    sectors = sectors[enb].orEmpty().sorted(),
                    bands = bands[enb].orEmpty().joinToString("+"),
                    bestLat = b?.lat,
                    bestLon = b?.lon,
                )
        }
        return out
    }

    /** One observed cell's position, for the "best observed position" rule: lowest GPS accuracy wins, strongest RSRP breaks ties. */
    private data class ObservedCellPosition(
        val lat: Double,
        val lon: Double,
        val accuracy: Float?,
        val rsrp: Int?,
    ) {
        fun isBetterThan(other: ObservedCellPosition): Boolean {
            val accA = accuracy ?: Float.MAX_VALUE
            val accB = other.accuracy ?: Float.MAX_VALUE
            if (accA != accB) return accA < accB
            return (rsrp ?: Int.MIN_VALUE) > (other.rsrp ?: Int.MIN_VALUE)
        }
    }

    private fun countNeighbors(observationId: Long): Int =
        try {
            readableDatabase
                .rawQuery("SELECT COUNT(*) FROM observation_neighbors WHERE observation_id = ?", arrayOf(observationId.toString()))
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (_: Exception) {
            0
        }

    // ---- Saved install points (Fase 5) ----

    /** Saves an install point: GPS + azimuth + signal at save time. [photoPath] attaches the field-camera photo (Fase 6); null when the point has no photo. Returns the row id, or -1. */
    fun savePoint(
        lat: Double,
        lon: Double,
        accuracy: Float?,
        azimuth: Float?,
        rsrp: Int?,
        photoPath: String? = null,
    ): Long {
        val values =
            ContentValues().apply {
                put("lat", lat)
                put("lon", lon)
                put("accuracy", accuracy)
                put("azimuth", azimuth)
                put("rsrp", rsrp)
                put("photo_path", photoPath)
                put("time", System.currentTimeMillis())
            }
        return writableDatabase.insert("points", null, values)
    }

    /** Saved points, newest first (drawn on the map; listed in Datos in Fase 6). */
    fun listPoints(): List<SavedPoint> {
        val out = ArrayList<SavedPoint>()
        readableDatabase.query("points", null, null, null, null, null, "time DESC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    SavedPoint(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
                        lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
                        accuracy = if (c.isNull(c.getColumnIndexOrThrow("accuracy"))) null else c.getFloat(c.getColumnIndexOrThrow("accuracy")),
                        azimuth = if (c.isNull(c.getColumnIndexOrThrow("azimuth"))) null else c.getFloat(c.getColumnIndexOrThrow("azimuth")),
                        rsrp = c.intOrNull("rsrp"),
                        time = c.getLong(c.getColumnIndexOrThrow("time")),
                        photoPath = c.getString(c.getColumnIndexOrThrow("photo_path")),
                    ),
                )
            }
        }
        return out
    }

    /** Deletes one saved point. The photo file is NOT deleted: the photo survives in Datos > Photos (Fase 6 rule). */
    fun deletePoint(id: Long) {
        writableDatabase.delete("points", "id = ?", arrayOf(id.toString()))
    }

    /** Positions of observed cells that carry one (amber dots on the map). */
    fun listObservedCellPositions(): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        readableDatabase.query("cells", arrayOf("lat", "lon"), "lat IS NOT NULL AND lon IS NOT NULL", null, null, null, null).use { c ->
            while (c.moveToNext()) {
                out.add(c.getDouble(0) to c.getDouble(1))
            }
        }
        return out
    }

    // ---- Datos (Fase 6): lists, detail, delete, counts ----

    /** Saved measurements, newest first, for Datos. */
    fun listObservations(): List<MeasurementSummary> {
        val out = ArrayList<MeasurementSummary>()
        readableDatabase.query("observations", null, null, null, null, null, "time DESC").use { c ->
            while (c.moveToNext()) {
                val rat = Rat.entries.firstOrNull { it.name == c.getString(c.getColumnIndexOrThrow("rat")) } ?: continue
                val id = c.getLong(c.getColumnIndexOrThrow("id"))
                out.add(
                    MeasurementSummary(
                        id = id,
                        rat = rat,
                        eci = c.longOrNull("eci"),
                        nci = c.longOrNull("nci"),
                        rsrp = c.intOrNull("rsrp"),
                        timeMs = c.getLong(c.getColumnIndexOrThrow("time")),
                        neighborCount = countNeighbors(id),
                    ),
                )
            }
        }
        return out
    }

    /** One measurement with its neighbor cells, for the Datos detail view. Null when the row is gone. */
    fun observationDetail(id: Long): ObservationDetail? {
        readableDatabase.query("observations", null, "id = ?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) return null
            val rat = Rat.entries.firstOrNull { it.name == c.getString(c.getColumnIndexOrThrow("rat")) } ?: return null
            val neighbors = ArrayList<NeighborRow>()
            readableDatabase.query("observation_neighbors", null, "observation_id = ?", arrayOf(id.toString()), null, null, null).use { n ->
                while (n.moveToNext()) {
                    val nrat = Rat.entries.firstOrNull { it.name == n.getString(n.getColumnIndexOrThrow("rat")) } ?: continue
                    neighbors.add(
                        NeighborRow(
                            rat = nrat,
                            eci = n.longOrNull("eci"),
                            nci = n.longOrNull("nci"),
                            pci = n.intOrNull("pci"),
                            earfcn = n.longOrNull("earfcn"),
                            band = n.getString(n.getColumnIndexOrThrow("band")),
                            rsrp = n.intOrNull("rsrp"),
                            rssi = n.intOrNull("rssi"),
                        ),
                    )
                }
            }
            return ObservationDetail(
                id = id,
                rat = rat,
                eci = c.longOrNull("eci"),
                nci = c.longOrNull("nci"),
                tac = c.intOrNull("tac"),
                pci = c.intOrNull("pci"),
                earfcn = c.longOrNull("earfcn"),
                band = c.getString(c.getColumnIndexOrThrow("band")),
                plmn = c.getString(c.getColumnIndexOrThrow("plmn")),
                rsrp = c.intOrNull("rsrp"),
                rsrq = c.intOrNull("rsrq"),
                rssi = c.intOrNull("rssi"),
                sinr = c.intOrNull("sinr"),
                lat = if (c.isNull(c.getColumnIndexOrThrow("lat"))) null else c.getDouble(c.getColumnIndexOrThrow("lat")),
                lon = if (c.isNull(c.getColumnIndexOrThrow("lon"))) null else c.getDouble(c.getColumnIndexOrThrow("lon")),
                accuracy = if (c.isNull(c.getColumnIndexOrThrow("accuracy"))) null else c.getFloat(c.getColumnIndexOrThrow("accuracy")),
                azimuth = if (c.isNull(c.getColumnIndexOrThrow("azimuth"))) null else c.getFloat(c.getColumnIndexOrThrow("azimuth")),
                source = c.getString(c.getColumnIndexOrThrow("source")) ?: "?",
                time = c.getLong(c.getColumnIndexOrThrow("time")),
                readingTime = c.longOrNull("reading_time"),
                neighbors = neighbors,
            )
        }
    }

    /** Deletes one measurement and its neighbor rows, in one transaction. */
    fun deleteObservation(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("observation_neighbors", "observation_id = ?", arrayOf(id.toString()))
            db.delete("observations", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Observed cells, strongest signal first, for Datos > Observed cells. */
    fun listObservedCells(): List<ObservedCellRow> {
        val out = ArrayList<ObservedCellRow>()
        readableDatabase.query("cells", null, null, null, null, null, "rsrp DESC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    ObservedCellRow(
                        eci = c.getLong(c.getColumnIndexOrThrow("eci")),
                        tac = c.intOrNull("tac"),
                        pci = c.intOrNull("pci"),
                        earfcn = c.longOrNull("earfcn"),
                        band = c.getString(c.getColumnIndexOrThrow("band")),
                        rsrp = c.intOrNull("rsrp"),
                        rsrq = c.intOrNull("rsrq"),
                        sinr = c.intOrNull("sinr"),
                        lat = if (c.isNull(c.getColumnIndexOrThrow("lat"))) null else c.getDouble(c.getColumnIndexOrThrow("lat")),
                        lon = if (c.isNull(c.getColumnIndexOrThrow("lon"))) null else c.getDouble(c.getColumnIndexOrThrow("lon")),
                        time = c.longOrNull("time"),
                    ),
                )
            }
        }
        return out
    }

    /** Removes one observed cell from the registry. The antenna catalog is an asset and is never touched. */
    fun deleteObservedCell(eci: Long) {
        writableDatabase.delete("cells", "eci = ?", arrayOf(eci.toString()))
    }

    // ---- Photos (Fase 6) ----

    /** Registers a photo the field camera saved (SignalX 1.0.9 insertPhoto). Returns the row id, or -1. */
    fun insertPhoto(
        path: String,
        lat: Double?,
        lon: Double?,
        azimuth: Float?,
        accuracy: Float?,
    ): Long {
        val values =
            ContentValues().apply {
                put("path", path)
                put("lat", lat)
                put("lon", lon)
                put("azimuth", azimuth)
                put("accuracy", accuracy)
                put("time", System.currentTimeMillis())
            }
        return writableDatabase.insert("photos", null, values)
    }

    /** Photos, newest first (Datos > Photos). */
    fun listPhotos(): List<PhotoRecord> {
        val out = ArrayList<PhotoRecord>()
        readableDatabase.query("photos", null, null, null, null, null, "time DESC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    PhotoRecord(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        path = c.getString(c.getColumnIndexOrThrow("path")) ?: "",
                        lat = if (c.isNull(c.getColumnIndexOrThrow("lat"))) null else c.getDouble(c.getColumnIndexOrThrow("lat")),
                        lon = if (c.isNull(c.getColumnIndexOrThrow("lon"))) null else c.getDouble(c.getColumnIndexOrThrow("lon")),
                        azimuth = if (c.isNull(c.getColumnIndexOrThrow("azimuth"))) null else c.getFloat(c.getColumnIndexOrThrow("azimuth")),
                        accuracy = if (c.isNull(c.getColumnIndexOrThrow("accuracy"))) null else c.getFloat(c.getColumnIndexOrThrow("accuracy")),
                        time = c.getLong(c.getColumnIndexOrThrow("time")),
                    ),
                )
            }
        }
        return out
    }

    /**
     * Deletes one photo row and returns its file path so the caller can
     * delete the file too. Any point that referenced the file keeps its
     * photo_path text but the file is gone — the point row is never
     * rewritten silently; Datos shows "photo file missing" for it.
     */
    fun deletePhoto(id: Long): String? {
        var path: String? = null
        readableDatabase.query("photos", arrayOf("path"), "id = ?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            if (c.moveToFirst()) path = c.getString(0)
        }
        writableDatabase.delete("photos", "id = ?", arrayOf(id.toString()))
        return path
    }

    /** Real counters for Diagnóstico (Fase 6). A read failure reads as 0 for that table, never a crash. */
    fun counts(): DbCounts {
        fun count(table: String): Int =
            try {
                readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            } catch (_: Exception) {
                0
            }
        return DbCounts(
            observations = count("observations"),
            neighbors = count("observation_neighbors"),
            cells = count("cells"),
            points = count("points"),
            photos = count("photos"),
            towerPositions = count("tower_positions"),
        )
    }

    /**
     * Deletes ALL SignalX field records (Fase 6, "Delete all field
     * data"): observations + neighbors, observed cells, points, photos
     * rows and manual antenna positions — the same tables SignalX 1.0.9
     * deleteAll cleared. Crash logs (files, outside the DB) and maps
     * survive by design; BunnyLock's prefs are never touched.
     */
    fun deleteAllFieldData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("observation_neighbors", null, null)
            db.delete("observations", null, null)
            db.delete("cells", null, null)
            db.delete("points", null, null)
            db.delete("photos", null, null)
            db.delete("tower_positions", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }
}

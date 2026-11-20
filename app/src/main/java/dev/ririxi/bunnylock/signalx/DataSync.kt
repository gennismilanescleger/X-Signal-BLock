// Field-data export / import, JSON (superprompt §6.4, §13; Fase 6).
//
// The format is SignalX 1.0.9's exportJson, extended — never replaced:
//   { "app": "SignalX", "version": 2, "observations": [...],
//     "cells": [...], "points": [...], "tower_positions": [...],
//     "photos": [...] }
//
// Version 1 files (SignalX 1.0.9: only cells/points/tower_positions,
// points with name/type and no signal) still import: the v1 fields
// are read, the missing ones stay null, nothing is rejected for being
// old. Version 2 adds observations (neighbors embedded in each one),
// points with azimuth/rsrp/photo_path, and the photos registry.
//
// The two rules that matter:
//
// 1. Export never deletes anything. Import never resets the base:
//    it MERGES. A row that is already here (same identity) is skipped,
//    never duplicated:
//      - observations: same (time, eci, nci) — neighbors merge by
//        (rat, pci, earfcn, rsrp) inside the matched observation;
//      - cells: upsert by eci; tower_positions: upsert by enb;
//      - points: same (lat, lon, time);
//      - photos: same (path, time), and only when the file actually
//        exists at that path — a photo row without its file is not
//        imported (a point keeps its photo_path only when its file
//        exists too; otherwise the path is dropped, not dangled).
// 2. One bad row never kills the import: it is counted in [errors]
//    and the rest still lands, in one transaction.

package dev.ririxi.bunnylock.signalx

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ImportResult(
    val observations: Int,
    val neighbors: Int,
    val cells: Int,
    val points: Int,
    val towerPositions: Int,
    val photos: Int,
    val skippedPhotos: Int,
    val errors: Int,
)

object FieldDataSync {
    private const val EXPORT_VERSION = 2

    fun export(db: SignalXDb): String {
        val root = JSONObject()
        root.put("app", "SignalX")
        root.put("version", EXPORT_VERSION)
        root.put("exported_at", System.currentTimeMillis())

        val observations = JSONArray()
        for (summary in db.listObservations()) {
            val detail = db.observationDetail(summary.id) ?: continue
            observations.put(
                JSONObject().apply {
                    put("rat", detail.rat.name)
                    if (detail.eci != null) put("eci", detail.eci)
                    if (detail.nci != null) put("nci", detail.nci)
                    if (detail.tac != null) put("tac", detail.tac)
                    if (detail.pci != null) put("pci", detail.pci)
                    if (detail.earfcn != null) put("earfcn", detail.earfcn)
                    if (detail.band != null) put("band", detail.band)
                    if (detail.plmn != null) put("plmn", detail.plmn)
                    if (detail.rsrp != null) put("rsrp", detail.rsrp)
                    if (detail.rsrq != null) put("rsrq", detail.rsrq)
                    if (detail.rssi != null) put("rssi", detail.rssi)
                    if (detail.sinr != null) put("sinr", detail.sinr)
                    if (detail.lat != null) put("lat", detail.lat)
                    if (detail.lon != null) put("lon", detail.lon)
                    if (detail.accuracy != null) put("accuracy", detail.accuracy)
                    if (detail.azimuth != null) put("azimuth", detail.azimuth)
                    put("source", detail.source)
                    put("time", detail.time)
                    if (detail.readingTime != null) put("reading_time", detail.readingTime)
                    val neighbors = JSONArray()
                    for (n in detail.neighbors) {
                        neighbors.put(
                            JSONObject().apply {
                                put("rat", n.rat.name)
                                if (n.eci != null) put("eci", n.eci)
                                if (n.nci != null) put("nci", n.nci)
                                if (n.pci != null) put("pci", n.pci)
                                if (n.earfcn != null) put("earfcn", n.earfcn)
                                if (n.band != null) put("band", n.band)
                                if (n.rsrp != null) put("rsrp", n.rsrp)
                                if (n.rssi != null) put("rssi", n.rssi)
                            },
                        )
                    }
                    put("neighbors", neighbors)
                },
            )
        }
        root.put("observations", observations)

        val cells = JSONArray()
        for (c in db.listObservedCells()) {
            cells.put(
                JSONObject().apply {
                    put("eci", c.eci)
                    if (c.tac != null) put("tac", c.tac)
                    if (c.pci != null) put("pci", c.pci)
                    if (c.earfcn != null) put("earfcn", c.earfcn)
                    if (c.band != null) put("band", c.band)
                    if (c.rsrp != null) put("rsrp", c.rsrp)
                    if (c.rsrq != null) put("rsrq", c.rsrq)
                    if (c.sinr != null) put("sinr", c.sinr)
                    if (c.lat != null) put("lat", c.lat)
                    if (c.lon != null) put("lon", c.lon)
                    if (c.time != null) put("time", c.time)
                },
            )
        }
        root.put("cells", cells)

        val points = JSONArray()
        for (p in db.listPoints()) {
            points.put(
                JSONObject().apply {
                    put("lat", p.lat)
                    put("lon", p.lon)
                    if (p.accuracy != null) put("accuracy", p.accuracy)
                    if (p.azimuth != null) put("azimuth", p.azimuth)
                    if (p.rsrp != null) put("rsrp", p.rsrp)
                    if (p.photoPath != null) put("photo_path", p.photoPath)
                    put("time", p.time)
                },
            )
        }
        root.put("points", points)

        val towers = JSONArray()
        for (t in db.listTowerPositions()) {
            towers.put(
                JSONObject().apply {
                    put("enb", t.enb)
                    put("lat", t.lat)
                    put("lon", t.lon)
                    put("source", t.source)
                    put("time", t.time)
                },
            )
        }
        root.put("tower_positions", towers)

        val photos = JSONArray()
        for (ph in db.listPhotos()) {
            photos.put(
                JSONObject().apply {
                    put("path", ph.path)
                    if (ph.lat != null) put("lat", ph.lat)
                    if (ph.lon != null) put("lon", ph.lon)
                    if (ph.azimuth != null) put("azimuth", ph.azimuth)
                    if (ph.accuracy != null) put("accuracy", ph.accuracy)
                    put("time", ph.time)
                },
            )
        }
        root.put("photos", photos)

        return root.toString(2)
    }

    /**
     * Merges [text] into [db]. Returns null when the text is not a
     * SignalX export at all (wrong app, broken JSON). A v1 file imports
     * fine — only the arrays it carries are merged.
     */
    fun import(
        db: SignalXDb,
        text: String,
    ): ImportResult? {
        val root =
            try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
        if (root.optString("app") != "SignalX") return null
        val writable = db.writableDatabase
        var observations = 0
        var neighbors = 0
        var cells = 0
        var points = 0
        var towers = 0
        var photos = 0
        var skippedPhotos = 0
        var errors = 0
        writable.beginTransaction()
        try {
            val obsArr = root.optJSONArray("observations")
            if (obsArr != null) {
                for (i in 0 until obsArr.length()) {
                    try {
                        val o = obsArr.getJSONObject(i)
                        val time = o.getLong("time")
                        val eci = if (o.has("eci")) o.optLong("eci") else null
                        val nci = if (o.has("nci")) o.optLong("nci") else null
                        val existingId = findObservation(writable, time, eci, nci)
                        val id =
                            if (existingId != null) {
                                existingId
                            } else {
                                val values = observationValues(o)
                                val newId = writable.insert("observations", null, values)
                                if (newId < 0) continue
                                observations++
                                newId
                            }
                        val nArr = o.optJSONArray("neighbors")
                        if (nArr != null) {
                            for (j in 0 until nArr.length()) {
                                try {
                                    val n = nArr.getJSONObject(j)
                                    if (insertNeighborIfMissing(writable, id, n, time)) neighbors++
                                } catch (_: Exception) {
                                    errors++
                                }
                            }
                        }
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            val cellsArr = root.optJSONArray("cells")
            if (cellsArr != null) {
                for (i in 0 until cellsArr.length()) {
                    try {
                        val o = cellsArr.getJSONObject(i)
                        val values =
                            ContentValues().apply {
                                put("eci", o.getLong("eci"))
                                if (o.has("tac")) put("tac", o.optInt("tac"))
                                if (o.has("pci")) put("pci", o.optInt("pci"))
                                if (o.has("earfcn")) put("earfcn", o.optLong("earfcn"))
                                if (o.has("band")) put("band", o.optString("band"))
                                if (o.has("rsrp")) put("rsrp", o.optInt("rsrp"))
                                if (o.has("rsrq")) put("rsrq", o.optInt("rsrq"))
                                if (o.has("sinr")) put("sinr", o.optInt("sinr"))
                                if (o.has("lat")) put("lat", o.optDouble("lat"))
                                if (o.has("lon")) put("lon", o.optDouble("lon"))
                                put("time", o.optLong("time", System.currentTimeMillis()))
                            }
                        if (writable.insertWithOnConflict("cells", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) cells++
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            val pointsArr = root.optJSONArray("points")
            if (pointsArr != null) {
                for (i in 0 until pointsArr.length()) {
                    try {
                        val o = pointsArr.getJSONObject(i)
                        val lat = o.getDouble("lat")
                        val lon = o.getDouble("lon")
                        val time = o.optLong("time", System.currentTimeMillis())
                        if (findPoint(writable, lat, lon, time)) continue
                        val photoPath = o.optString("photo_path", null)?.takeIf { File(it).exists() }
                        val values =
                            ContentValues().apply {
                                put("lat", lat)
                                put("lon", lon)
                                if (o.has("accuracy")) put("accuracy", o.optDouble("accuracy").toFloat())
                                if (o.has("azimuth")) put("azimuth", o.optDouble("azimuth").toFloat())
                                if (o.has("rsrp")) put("rsrp", o.optInt("rsrp"))
                                put("photo_path", photoPath)
                                put("time", time)
                            }
                        if (writable.insert("points", null, values) >= 0) points++
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            val towersArr = root.optJSONArray("tower_positions")
            if (towersArr != null) {
                for (i in 0 until towersArr.length()) {
                    try {
                        val o = towersArr.getJSONObject(i)
                        val values =
                            ContentValues().apply {
                                put("enb", o.getInt("enb"))
                                put("lat", o.getDouble("lat"))
                                put("lon", o.getDouble("lon"))
                                put("source", o.optString("source", "manual"))
                                put("time", o.optLong("time", System.currentTimeMillis()))
                            }
                        if (writable.insertWithOnConflict("tower_positions", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) towers++
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            val photosArr = root.optJSONArray("photos")
            if (photosArr != null) {
                for (i in 0 until photosArr.length()) {
                    try {
                        val o = photosArr.getJSONObject(i)
                        val path = o.getString("path")
                        val time = o.optLong("time", System.currentTimeMillis())
                        if (!File(path).exists()) {
                            skippedPhotos++
                            continue
                        }
                        if (findPhoto(writable, path, time)) continue
                        val values =
                            ContentValues().apply {
                                put("path", path)
                                if (o.has("lat")) put("lat", o.optDouble("lat"))
                                if (o.has("lon")) put("lon", o.optDouble("lon"))
                                if (o.has("azimuth")) put("azimuth", o.optDouble("azimuth").toFloat())
                                if (o.has("accuracy")) put("accuracy", o.optDouble("accuracy").toFloat())
                                put("time", time)
                            }
                        if (writable.insert("photos", null, values) >= 0) photos++
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        return ImportResult(observations, neighbors, cells, points, towers, photos, skippedPhotos, errors)
    }

    private fun observationValues(o: JSONObject): ContentValues =
        ContentValues().apply {
            put("rat", o.optString("rat", "LTE"))
            if (o.has("eci")) put("eci", o.optLong("eci"))
            if (o.has("nci")) put("nci", o.optLong("nci"))
            if (o.has("tac")) put("tac", o.optInt("tac"))
            if (o.has("pci")) put("pci", o.optInt("pci"))
            if (o.has("earfcn")) put("earfcn", o.optLong("earfcn"))
            if (o.has("band")) put("band", o.optString("band"))
            if (o.has("plmn")) put("plmn", o.optString("plmn"))
            if (o.has("rsrp")) put("rsrp", o.optInt("rsrp"))
            if (o.has("rsrq")) put("rsrq", o.optInt("rsrq"))
            if (o.has("rssi")) put("rssi", o.optInt("rssi"))
            if (o.has("sinr")) put("sinr", o.optInt("sinr"))
            if (o.has("lat")) put("lat", o.optDouble("lat"))
            if (o.has("lon")) put("lon", o.optDouble("lon"))
            if (o.has("accuracy")) put("accuracy", o.optDouble("accuracy"))
            if (o.has("azimuth")) put("azimuth", o.optDouble("azimuth"))
            put("source", o.optString("source", "MANUAL"))
            put("time", o.getLong("time"))
            if (o.has("reading_time")) put("reading_time", o.optLong("reading_time"))
        }

    /** The observation already here with the same (time, eci, nci), or null. NULL never equals NULL in SQL, so eci/nci are compared in Kotlin. */
    private fun findObservation(
        db: SQLiteDatabase,
        time: Long,
        eci: Long?,
        nci: Long?,
    ): Long? {
        db.query("observations", arrayOf("id", "eci", "nci"), "time = ?", arrayOf(time.toString()), null, null, null).use { c ->
            while (c.moveToNext()) {
                val rowEci = if (c.isNull(1)) null else c.getLong(1)
                val rowNci = if (c.isNull(2)) null else c.getLong(2)
                if (rowEci == eci && rowNci == nci) return c.getLong(0)
            }
        }
        return null
    }

    private fun insertNeighborIfMissing(
        db: SQLiteDatabase,
        observationId: Long,
        n: JSONObject,
        time: Long,
    ): Boolean {
        val rat = n.optString("rat", "LTE")
        val pci = if (n.has("pci")) n.optInt("pci") else null
        val earfcn = if (n.has("earfcn")) n.optLong("earfcn") else null
        val rsrp = if (n.has("rsrp")) n.optInt("rsrp") else null
        db.query(
            "observation_neighbors",
            arrayOf("id"),
            "observation_id = ? AND rat = ? AND " +
                (if (pci == null) "pci IS NULL" else "pci = ?") + " AND " +
                (if (earfcn == null) "earfcn IS NULL" else "earfcn = ?") + " AND " +
                (if (rsrp == null) "rsrp IS NULL" else "rsrp = ?"),
            buildList {
                add(observationId.toString())
                add(rat)
                pci?.let { add(it.toString()) }
                earfcn?.let { add(it.toString()) }
                rsrp?.let { add(it.toString()) }
            }.toTypedArray(),
            null, null, null,
        ).use { c ->
            if (c.moveToFirst()) return false
        }
        val values =
            ContentValues().apply {
                put("observation_id", observationId)
                put("rat", rat)
                if (n.has("eci")) put("eci", n.optLong("eci"))
                if (n.has("nci")) put("nci", n.optLong("nci"))
                if (pci != null) put("pci", pci)
                if (earfcn != null) put("earfcn", earfcn)
                if (n.has("band")) put("band", n.optString("band"))
                if (rsrp != null) put("rsrp", rsrp)
                if (n.has("rssi")) put("rssi", n.optInt("rssi"))
                put("time", time)
            }
        return db.insert("observation_neighbors", null, values) >= 0
    }

    private fun findPoint(
        db: SQLiteDatabase,
        lat: Double,
        lon: Double,
        time: Long,
    ): Boolean {
        db.query("points", arrayOf("id"), "lat = ? AND lon = ? AND time = ?", arrayOf(lat.toString(), lon.toString(), time.toString()), null, null, null, "1").use { c ->
            return c.moveToFirst()
        }
    }

    private fun findPhoto(
        db: SQLiteDatabase,
        path: String,
        time: Long,
    ): Boolean {
        db.query("photos", arrayOf("id"), "path = ? AND time = ?", arrayOf(path, time.toString()), null, null, null, "1").use { c ->
            return c.moveToFirst()
        }
    }
}

package com.travelguard.app.domain.geo

import com.travelguard.app.domain.model.LatLngPoint
import com.travelguard.app.domain.model.LocationSample
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MAX_PLAUSIBLE_SPEED_MPS = 95.0 // ~342 km/h (high-speed rail ceiling)
    private const val MAX_ACCEPTABLE_ACCURACY_METERS = 150f

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun kmToMeters(km: Float): Double = km.toDouble() * 1000.0

    fun isSampleReliable(previous: LocationSample?, candidate: LocationSample): Boolean {
        if (candidate.latitude !in -90.0..90.0 || candidate.longitude !in -180.0..180.0) {
            return false
        }
        if (candidate.accuracyMeters > MAX_ACCEPTABLE_ACCURACY_METERS) {
            return false
        }
        if (previous != null) {
            val dtSeconds = (candidate.timestampMillis - previous.timestampMillis) / 1000.0
            if (dtSeconds > 0.0) {
                val jumpMeters = distanceMeters(
                    previous.latitude,
                    previous.longitude,
                    candidate.latitude,
                    candidate.longitude
                )
                if (jumpMeters / dtSeconds > MAX_PLAUSIBLE_SPEED_MPS && jumpMeters > 500.0) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Computes minimum distance in meters from [point] to any segment of [polyline],
     * plus the index of the closest segment start vertex.
     */
    fun distanceToPolylineMeters(
        point: LatLngPoint,
        polyline: List<LatLngPoint>
    ): Pair<Double, Int> {
        if (polyline.isEmpty()) return Double.MAX_VALUE to -1
        if (polyline.size == 1) {
            return distanceMeters(
                point.latitude, point.longitude,
                polyline[0].latitude, polyline[0].longitude
            ) to 0
        }

        var minDistance = Double.MAX_VALUE
        var closestSegIndex = 0

        for (i in 0 until polyline.size - 1) {
            val d = distanceToSegmentMeters(point, polyline[i], polyline[i + 1])
            if (d < minDistance) {
                minDistance = d
                closestSegIndex = i
            }
        }
        return minDistance to closestSegIndex
    }

    fun distanceToSegmentMeters(
        p: LatLngPoint,
        a: LatLngPoint,
        b: LatLngPoint
    ): Double {
        val refLatRad = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val metersPerDegLat = 111_132.92
        val metersPerDegLon = 111_412.84 * cos(refLatRad).coerceAtLeast(0.01)

        val ax = 0.0
        val ay = 0.0
        val bx = (b.longitude - a.longitude) * metersPerDegLon
        val by = (b.latitude - a.latitude) * metersPerDegLat
        val px = (p.longitude - a.longitude) * metersPerDegLon
        val py = (p.latitude - a.latitude) * metersPerDegLat

        val abLenSq = bx * bx + by * by
        if (abLenSq <= 1e-6) {
            return sqrt(px * px + py * py)
        }
        val t = max(0.0, min(1.0, (px * bx + py * by) / abLenSq))
        val projX = ax + t * bx
        val projY = ay + t * by
        val dx = px - projX
        val dy = py - projY
        return sqrt(dx * dx + dy * dy)
    }

    fun remainingRouteDistanceMeters(
        current: LatLngPoint,
        polyline: List<LatLngPoint>
    ): Double {
        if (polyline.size < 2) return 0.0
        val (_, closestIdx) = distanceToPolylineMeters(current, polyline)
        val nextVertexIdx = (closestIdx + 1).coerceAtMost(polyline.lastIndex)
        var total = distanceMeters(
            current.latitude,
            current.longitude,
            polyline[nextVertexIdx].latitude,
            polyline[nextVertexIdx].longitude
        )
        for (i in nextVertexIdx until polyline.lastIndex) {
            total += distanceMeters(
                polyline[i].latitude,
                polyline[i].longitude,
                polyline[i + 1].latitude,
                polyline[i + 1].longitude
            )
        }
        return total
    }

    fun computeProgressPercent(totalMeters: Double, remainingMeters: Double): Int {
        if (totalMeters <= 1.0) return 0
        val travelled = (totalMeters - remainingMeters).coerceIn(0.0, totalMeters)
        return ((travelled / totalMeters) * 100.0).toInt().coerceIn(0, 100)
    }
}

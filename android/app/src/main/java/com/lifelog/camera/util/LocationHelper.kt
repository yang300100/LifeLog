package com.lifelog.camera.util

import android.content.Context
import android.location.Geocoder
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * 位置辅助 — 判断用户大致环境（在家/公司/户外）
 * 需要 ACCESS_FINE_LOCATION 权限（App 已有）
 */
object LocationHelper {

    enum class Place { UNKNOWN, HOME, WORK, OUTDOOR }

    data class LocationContext(val place: Place, val detail: String)

    /** 获取当前位置上下文 */
    suspend fun getLocationContext(context: Context): LocationContext = withContext(Dispatchers.IO) {
        try {
            val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
            val location = withTimeoutOrNull(3000L) { fusedClient.lastLocation.await() } ?: return@withContext LocationContext(Place.UNKNOWN, "无法获取位置")

            // 尝试逆地理编码
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = withTimeoutOrNull(2000L) { geocoder.getFromLocation(location.latitude, location.longitude, 1) }
            val address = addresses?.firstOrNull()
            val feature = address?.featureName ?: ""
            val thoroughfare = address?.thoroughfare ?: ""

            val place = when {
                feature.contains("家", ignoreCase = true) || feature.contains("home", ignoreCase = true) -> Place.HOME
                thoroughfare.contains("科技", ignoreCase = true) || thoroughfare.contains("产业园", ignoreCase = true) -> Place.WORK
                feature.contains("公司", ignoreCase = true) || feature.contains("office", ignoreCase = true) -> Place.WORK
                address == null -> Place.OUTDOOR
                else -> Place.OUTDOOR
            }

            val detail = (address?.let {
                "${it.locality ?: ""}${it.subLocality ?: ""} ${thoroughfare}"
            } ?: "").trim().ifBlank { "户外" }

            LocationContext(place, detail)
        } catch (e: Exception) {
            LocationContext(Place.UNKNOWN, "定位失败")
        }
    }
}

package com.timepic.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.timepic.app.model.LocationSource
import com.timepic.app.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LocationResult(val place: Place, val message: String)
class LocationFailure(message: String) : Exception(message)

class SystemLocationRepository(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    // Older Geocoder calls are blocking Binder calls. A cancellable bridge prevents UI timeouts
    // from waiting for an unresponsive vendor service to return.
    private val geocoderWorkers = Executors.newFixedThreadPool(2) {
        Thread(it, "timepic-geocoder").apply { isDaemon = true }
    }

    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
        granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): LocationResult {
        if (!hasPermission()) throw LocationFailure("请允许定位，或直接填写地址")
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            throw LocationFailure("手机定位服务未开启，可打开系统定位或手动填写地址")
        }
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it != LocationManager.GPS_PROVIDER || fine }
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw LocationFailure("当前没有可用的系统定位服务，请手动填写地址")
        val location = withTimeoutOrNull(18_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellations = providers.map { CancellationSignal() }
                var remaining = providers.size
                continuation.invokeOnCancellation { cancellations.forEach { it.cancel() } }
                providers.forEachIndexed { index, provider ->
                    try {
                        LocationManagerCompat.getCurrentLocation(
                            manager, provider, cancellations[index],
                            ContextCompat.getMainExecutor(context)
                        ) { fix ->
                            if (continuation.isActive) {
                                remaining--
                                val fresh = fix != null &&
                                    SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos < 120_000_000_000L
                                if (fresh) {
                                    continuation.resume(fix)
                                    cancellations.forEach { it.cancel() }
                                } else if (remaining == 0) continuation.resume(null)
                            }
                        }
                    } catch (_: SecurityException) {
                        remaining--
                        if (remaining == 0 && continuation.isActive) continuation.resume(null)
                    } catch (_: IllegalArgumentException) {
                        remaining--
                        if (remaining == 0 && continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } ?: throw LocationFailure("暂时无法获取位置，请重试或手动填写地址")
        val addresses = try {
            reverse(location.latitude, location.longitude)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
        val address = addresses.firstOrNull()?.let(::addressText).orEmpty()
        val coordinateText = String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
        return LocationResult(
            Place(address.ifBlank { coordinateText }, location.latitude, location.longitude, LocationSource.CURRENT),
            if (address.isBlank()) "已获取坐标，地址解析不可用，可手动填写地址"
            else if (!fine) "已获取大致位置，可编辑地址" else "已更新当前位置"
        )
    }

    suspend fun search(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        if (!Geocoder.isPresent()) throw LocationFailure("此设备没有系统地址搜索服务，可直接使用输入的地址")
        val result = withTimeoutOrNull(10_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                geocode { coder, listener -> coder.getFromLocationName(query, 8, listener) }
            } else legacyGeocode { it.getFromLocationName(query, 8).orEmpty() }
        } ?: throw LocationFailure("地址搜索超时，可直接使用输入的地址")
        return result.map {
            Place(addressText(it), if (it.hasLatitude()) it.latitude else null,
                if (it.hasLongitude()) it.longitude else null, LocationSource.SEARCH)
        }.filter { it.text.isNotBlank() }.distinctBy { it.text }
    }

    private suspend fun reverse(lat: Double, lon: Double): List<Address> {
        if (!Geocoder.isPresent()) return emptyList()
        return withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                geocode { coder, listener -> coder.getFromLocation(lat, lon, 1, listener) }
            } else legacyGeocode { it.getFromLocation(lat, lon, 1).orEmpty() }
        }.orEmpty()
    }

    @androidx.annotation.RequiresApi(33)
    private suspend fun geocode(
        request: (Geocoder, Geocoder.GeocodeListener) -> Unit
    ): List<Address> = suspendCancellableCoroutine { continuation ->
        try {
            request(Geocoder(context, Locale.SIMPLIFIED_CHINESE), object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(addresses)
                }
                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resumeWithException(
                        LocationFailure("系统地址服务暂不可用，可直接填写地址"))
                }
            })
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun legacyGeocode(request: (Geocoder) -> List<Address>): List<Address> =
        suspendCancellableCoroutine { continuation ->
            val future = geocoderWorkers.submit {
                try {
                    val result = request(Geocoder(context, Locale.SIMPLIFIED_CHINESE))
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }

    private fun addressText(address: Address): String =
        (if (address.maxAddressLineIndex >= 0) address.getAddressLine(0) else
            listOfNotNull(address.adminArea, address.locality, address.subLocality,
                address.thoroughfare, address.featureName).distinct().joinToString(""))
            .orEmpty().take(160)

    fun close() { geocoderWorkers.shutdownNow() }
}

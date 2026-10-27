package com.koolon.macro

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** 집 반경 안으로 들어올 때 arrive 매크로 실행 */
class LocationWatcher(private val svc: MacroService) : LocationListener {
    private val lm = svc.getSystemService(LocationManager::class.java)
    private var inside: Boolean? = null

    fun start() {
        stop()
        if (MacroStore.home(svc) == null) return
        if (svc.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val p = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
            else LocationManager.GPS_PROVIDER
            lm.requestLocationUpdates(p, 60_000L, 50f, this, Looper.getMainLooper())
        } catch (e: Exception) {
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(this)
        } catch (e: Exception) {
        }
        inside = null
    }

    override fun onLocationChanged(l: Location) {
        val (lat, lon) = MacroStore.home(svc) ?: return
        val home = Location("home").apply { latitude = lat; longitude = lon }
        val now = l.distanceTo(home) <= RADIUS_M
        if (inside == false && now) {
            MacroStore.all(svc).filter { it.arrive }.forEach { svc.runMacro(it.name) }
        }
        inside = now
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object {
        const val RADIUS_M = 200f
    }
}

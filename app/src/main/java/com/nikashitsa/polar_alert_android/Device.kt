package com.nikashitsa.polar_alert_android
import com.polar.sdk.api.model.PolarDeviceInfo

class Device(private val polarDeviceInfo: PolarDeviceInfo) {
    override fun toString(): String {
        return polarDeviceInfo.name
    }

    val id: String get() = this.polarDeviceInfo.deviceId
}
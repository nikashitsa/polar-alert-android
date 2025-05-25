package com.nikashitsa.polar_alert_android
import com.polar.sdk.api.model.PolarDeviceInfo

class Device() {
    private lateinit var polarDeviceInfo: PolarDeviceInfo

    constructor(polarDeviceInfo: PolarDeviceInfo) : this() {
        this.polarDeviceInfo = polarDeviceInfo
    }

    override fun toString(): String {
        return polarDeviceInfo.name
    }

    val id: String get() {
        return this.polarDeviceInfo.deviceId.ifEmpty { this.polarDeviceInfo.address }
    }
}
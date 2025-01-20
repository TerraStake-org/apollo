package io.muun.apollo.data.os

import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import io.muun.apollo.data.net.ConnectivityInfoProvider
import io.muun.apollo.data.net.NetworkInfoProvider
import io.muun.apollo.data.net.TrafficStatsInfoProvider
import kotlinx.serialization.Serializable
import javax.inject.Inject

private const val UNSUPPORTED = -1
private const val UNKNOWN = -2

class BackgroundExecutionMetricsProvider @Inject constructor(
    private val context: Context,
    private val hardwareCapabilitiesProvider: HardwareCapabilitiesProvider,
    private val telephonyInfoProvider: TelephonyInfoProvider,
    private val networkInfoProvider: NetworkInfoProvider,
    private val appInfoProvider: AppInfoProvider,
    private val connectivityInfoProvider: ConnectivityInfoProvider,
    private val activityManagerInfoProvider: ActivityManagerInfoProvider,
    private val resourcesInfoProvider: ResourcesInfoProvider,
    private val systemCapabilitiesProvider: SystemCapabilitiesProvider,
    private val dateTimeZoneProvider: DateTimeZoneProvider,
    private val localeInfoProvider: LocaleInfoProvider,
    private val trafficStatsInfoProvider: TrafficStatsInfoProvider,
    private val nfcProvider: NfcProvider,
) {

    private val powerManager: PowerManager by lazy {
        context.getSystemService(POWER_SERVICE) as PowerManager
    }

    fun run(): BackgroundExecutionMetrics =
        BackgroundExecutionMetrics(
            System.currentTimeMillis(),
            getBatteryLevel(),
            getMaxBatteryLevel(),
            getBatteryHealth(),
            getBatteryDischargePrediction(),
            getBatteryStatus(),
            hardwareCapabilitiesProvider.getTotalInternalStorageInBytes(),
            hardwareCapabilitiesProvider.getFreeInternalStorageInBytes(),
            hardwareCapabilitiesProvider.getFreeExternalStorageInBytes().toTypedArray(),
            hardwareCapabilitiesProvider.getTotalExternalStorageInBytes().toTypedArray(),
            hardwareCapabilitiesProvider.getTotalRamInBytes(),
            hardwareCapabilitiesProvider.getFreeRamInBytes(),
            telephonyInfoProvider.dataState,
            telephonyInfoProvider.getSimStates().toTypedArray(),
            getCurrentTransport(),
            SystemClock.uptimeMillis(),
            SystemClock.elapsedRealtime(),
            hardwareCapabilitiesProvider.bootCount,
            localeInfoProvider.language,
            dateTimeZoneProvider.timeZoneOffsetSeconds,
            telephonyInfoProvider.region.orElse(""),
            telephonyInfoProvider.simRegion,
            appInfoProvider.appDatadir,
            connectivityInfoProvider.vpnState,
            activityManagerInfoProvider.appImportance,
            resourcesInfoProvider.displayMetrics,
            systemCapabilitiesProvider.usbConnected,
            systemCapabilitiesProvider.usbPersistConfig,
            systemCapabilitiesProvider.bridgeEnabled,
            systemCapabilitiesProvider.bridgeDaemonStatus,
            systemCapabilitiesProvider.developerEnabled,
            connectivityInfoProvider.proxyHttp,
            connectivityInfoProvider.proxyHttps,
            connectivityInfoProvider.proxySocks,
            dateTimeZoneProvider.autoDateTime,
            dateTimeZoneProvider.autoTimeZone,
            dateTimeZoneProvider.timeZoneId,
            localeInfoProvider.dateFormat,
            localeInfoProvider.regionCode,
            dateTimeZoneProvider.calendarIdentifier,
            trafficStatsInfoProvider.androidMobileRxTraffic,
            telephonyInfoProvider.simOperatorId,
            telephonyInfoProvider.mobileNetworkId,
            telephonyInfoProvider.mobileRoaming,
            telephonyInfoProvider.mobileDataStatus,
            telephonyInfoProvider.mobileRadioType,
            telephonyInfoProvider.mobileDataActivity,
            connectivityInfoProvider.networkLink,
            nfcProvider.hasNfcFeature(),
            nfcProvider.hasNfcAdapter,
            nfcProvider.isNfcEnabled,
            nfcProvider.getNfcAntennaPosition().map { "${it.first};${it.second}" }.toTypedArray(),
            nfcProvider.deviceSizeInMm?.let { "${it.first};${it.second}" } ?: "",
            nfcProvider.isDeviceFoldable,
            activityManagerInfoProvider.isBackgroundRestricted
        )

    @Suppress("ArrayInDataClass")
    @Serializable
    data class BackgroundExecutionMetrics(
        private val epochInMilliseconds: Long,
        private val batteryLevel: Int,
        private val maxBatteryLevel: Int,
        private val batteryHealth: String,
        private val batteryDischargePrediction: Long?,
        private val batteryState: String,
        private val totalInternalStorage: Long,
        private val freeInternalStorage: Long,
        private val freeExternalStorage: Array<Long>,
        private val totalExternalStorage: Array<Long>,
        private val totalRamStorage: Long,
        private val freeRamStorage: Long,
        private val dataState: String,
        private val simStates: Array<String>,
        private val networkTransport: String,
        private val androidUptimeMillis: Long,
        private val androidElapsedRealtimeMillis: Long,
        private val androidBootCount: Int,
        private val language: String,
        private val timeZoneOffsetInSeconds: Long,
        private val telephonyNetworkRegion: String,
        private val simRegion: String,
        private val appDataDir: String,
        private val vpnState: Int,
        private val appImportance: Int,
        private val displayMetrics: ResourcesInfoProvider.DisplayMetricsInfo,
        private val usbConnected: Int,
        private val usbPersistConfig: String,
        private val bridgeEnabled: Int,
        private val bridgeDaemonStatus: String,
        private val developerEnabled: Int,
        private val proxyHttp: String,
        private val proxyHttps: String,
        private val proxySocks: String,
        private val autoDateTime: Int,
        private val autoTimeZone: Int,
        private val timeZoneId: String,
        private val androidDateFormat: String,
        private val regionCode: String,
        private val androidCalendarIdentifier: String,
        private val androidMobileRxTraffic: Long,
        private val androidSimOperatorId: String,
        private val androidMobileOperatorId: String,
        private val androidMobileRoaming: Boolean,
        private val androidMobileDataStatus: Int,
        private val androidMobileRadioType: Int,
        private val androidMobileDataActivity: Int,
        private val androidNetworkLink: ConnectivityInfoProvider.NetworkLink?,
        private val androidHasNfcFeature: Boolean,
        private val androidHasNfcAdapter: Boolean,
        private val androidNfcEnabled: Boolean,
        private val androidNfcAntennaPositions: Array<String>, // in mms starting bottom-left
        private val androidDeviceSizeInMms: String,
        private val androidFoldableDevice: Boolean?,
        private val isBackgroundRestricted: Boolean
    )

    /**
     * Returns the device battery health, which will be a string constant representing the general
     * health of this device. Note: Android docs do not explain what these values exactly mean.
     */
    private fun getBatteryHealth(): String =
        getBatteryHealthText(getBatteryProperty(BatteryManager.EXTRA_HEALTH))

    /**
     * Returns the device battery status, which will be a string constant with one of the following
     * values:
     * UNPLUGGED:   The device isn’t plugged into power; the battery is discharging.
     * CHARGING:    The device is plugged into power and the battery is less than 100% charged.
     * FULL:        The device is plugged into power and the battery is 100% charged.
     * UNKNOWN:     The battery status for the device can’t be determined.
     * UNREADABLE:  The battery status was unreadable/unrecognizable.
     */
    private fun getBatteryStatus(): String =
        getBatteryStatusText(getBatteryProperty(BatteryManager.EXTRA_STATUS))

    /**
     * (Android only and Android 12+ only) Returns the current battery life remaining estimate,
     * expressed in nanoseconds. Will be UNKNOWN (-2) if the device is powered, charging, or an error
     * was encountered. For pre Android 12 devices it will be UNSUPPORTED (-1).
     */
    private fun getBatteryDischargePrediction(): Long =
        if (OS.supportsBatteryDischargePrediction()) {
            powerManager.batteryDischargePrediction?.toNanos() ?: UNKNOWN.toLong()
        } else {
            UNSUPPORTED.toLong()
        }

    private fun getBatteryIntent(): Intent? =
        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            .let { intentFilter ->
                context.registerReceiver(null, intentFilter)
            }

    private fun getBatteryProperty(propertyName: String) =
        getBatteryIntent()?.getIntExtra(propertyName, -1) ?: -1

    /**
     * Returns the current battery level, an integer from 0 to EXTRA_SCALE/MaxBatteryLevel.
     */
    private fun getBatteryLevel() =
        getBatteryProperty(BatteryManager.EXTRA_LEVEL)

    /**
     * Returns an integer representing the maximum battery level.
     */
    private fun getMaxBatteryLevel(): Int =
        getBatteryProperty(BatteryManager.EXTRA_SCALE)

    private fun getBatteryHealthText(batteryHealth: Int): String {
        return when (batteryHealth) {
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_UNKNOWN -> "UNKNOWN"
            else -> "UNREADABLE"
        }
    }

    /**
     * Translate Android's BatteryManager battery status int constants into one of our domain
     * values. Note that Android docs don't really explain what these values mean.
     */
    private fun getBatteryStatusText(batteryStatus: Int): String {
        return when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "UNPLUGGED"
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
            else -> "UNREADABLE"
        }
    }

    /**
     * While NetworkInfo (used in networkInfoProvider) has been deprecated, its functionality
     * is complemented by ConnectivityManager methods for newer APIs. Backward compatibility
     * is maintained in the response values to ensure consistent data handling across all
     * Android versions
     */
    private fun getCurrentTransport(): String {
        return if (OS.supportsActiveNetwork()) {
            connectivityInfoProvider.currentTransportNewerApi
        } else {
            networkInfoProvider.currentTransport
        }

    }

}
package io.muun.apollo.domain.action.realtime

import io.muun.apollo.data.net.HoustonClient
import io.muun.apollo.data.preferences.FeeWindowRepository
import io.muun.apollo.data.preferences.MinFeeRateRepository
import io.muun.apollo.data.preferences.TransactionSizeRepository
import io.muun.apollo.domain.action.base.BaseAsyncAction0
import io.muun.apollo.domain.libwallet.LibwalletService
import io.muun.apollo.domain.model.MuunFeature
import io.muun.apollo.domain.model.RealTimeFees
import io.muun.apollo.domain.selector.FeatureSelector
import io.muun.apollo.domain.utils.toVoid
import io.muun.common.Rules
import io.muun.common.model.UtxoStatus
import io.muun.common.rx.RxHelper
import rx.Observable
import timber.log.Timber
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Update fees data, such as fee window and fee bump functions.
 */

@Singleton
class PreloadFeeDataAction @Inject constructor(
    private val houstonClient: HoustonClient,
    private val feeWindowRepository: FeeWindowRepository,
    private val minFeeRateRepository: MinFeeRateRepository,
    private val transactionSizeRepository: TransactionSizeRepository,
    private val featureSelector: FeatureSelector,
    private val libwalletService: LibwalletService,
) : BaseAsyncAction0<Void>() {

    companion object {
        private const val throttleIntervalInMilliseconds: Long = 10 * 1000
    }

    private var lastSyncTime: Date = Date(0) // Init with distant past

    /**
     * Force re-fetch of Houston's RealTimeFees, bypassing throttling logic.
     */
    fun runForced(): Observable<Void> {
        return syncRealTimeFees()
    }

    fun runIfDataIsInvalidated() {
        super.run(Observable.defer {
            if (libwalletService.areFeeBumpFunctionsInvalidated()) {
                return@defer this.syncRealTimeFees()
            } else {
                return@defer Observable.just(null)
            }
        })
    }

    override fun action(): Observable<Void> {
        return Observable.defer {
            if (shouldUpdateData()) {
                return@defer syncRealTimeFees()
            } else {
                return@defer Observable.just(null).toVoid()
            }
        }
    }

    private fun syncRealTimeFees(): Observable<Void> {
        if (!featureSelector.get(MuunFeature.EFFECTIVE_FEES_CALCULATION)) {
            return Observable.just(null)
        }

        Timber.d("[Sync] Updating realTime fees data")

        transactionSizeRepository.nextTransactionSize?.let { nts ->
            if (nts.unconfirmedUtxos.isEmpty()) {
                // If there are no unconfirmed UTXOs, it means there are no fee bump functions.
                // Remove the fee bump functions by storing an empty list.
                libwalletService.persistFeeBumpFunctions(emptyList())
            }
            return houstonClient.fetchRealTimeFees(nts.unconfirmedUtxos)
                .doOnNext { realTimeFees: RealTimeFees ->
                    Timber.d("[Sync] Saving updated fees")
                    storeFeeData(realTimeFees)
                    libwalletService.persistFeeBumpFunctions(realTimeFees.feeBumpFunctions)
                    lastSyncTime = Date()
                }
                .map(RxHelper::toVoid)
        }

        Timber.e("syncRealTimeFees was called without a local valid NTS")
        return Observable.just(null)
    }

    private fun storeFeeData(realTimeFees: RealTimeFees) {
        feeWindowRepository.store(realTimeFees.feeWindow)
        val minMempoolFeeRateInSatsPerWeightUnit =
            Rules.toSatsPerWeight(realTimeFees.minMempoolFeeRateInSatPerVbyte)
        minFeeRateRepository.store(minMempoolFeeRateInSatsPerWeightUnit)
    }

    private fun shouldUpdateData(): Boolean {
        val nowInMilliseconds = Date().time
        val secondsElapsedInMilliseconds = nowInMilliseconds - lastSyncTime.time
        return secondsElapsedInMilliseconds >= throttleIntervalInMilliseconds
    }
}
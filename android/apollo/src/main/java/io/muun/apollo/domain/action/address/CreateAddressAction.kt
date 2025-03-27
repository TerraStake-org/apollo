package io.muun.apollo.domain.action.address

import io.muun.apollo.data.preferences.KeysRepository
import io.muun.apollo.domain.action.base.BaseAsyncAction0
import io.muun.apollo.domain.libwallet.LibwalletBridge
import io.muun.apollo.domain.model.MuunAddressGroup
import io.muun.apollo.domain.utils.validateIndexBounds
import io.muun.common.Rules
import io.muun.common.crypto.hd.Schema
import io.muun.common.utils.RandomGenerator
import org.bitcoinj.core.NetworkParameters
import rx.Observable
import timber.log.Timber
import javax.inject.Inject

class CreateAddressAction @Inject constructor(
    private val keysRepository: KeysRepository,
    private val networkParameters: NetworkParameters,
    private val syncExternalAddressIndexes: SyncExternalAddressIndexesAction
) : BaseAsyncAction0<MuunAddressGroup>() {

    companion object {
        private const val TAG = "CreateAddressAction"
    }

    /**
     * Creates a new MuunAddressGroup containing addresses of different versions.
     * Also triggers an async sync of address indexes with Houston.
     */
    override fun action(): Observable<MuunAddressGroup> =
        Observable.fromCallable { createMuunAddressGroup() }
            .doOnNext { 
                Timber.tag(TAG).d("Created new address group, triggering index sync")
                syncExternalAddressIndexes.run() // Fire-and-forget sync
            }
            .doOnError { error ->
                Timber.tag(TAG).e(error, "Failed to create address group")
            }

    private fun createMuunAddressGroup(): MuunAddressGroup {
        val (maxUsedIndex, maxWatchingIndex) = keysRepository.run {
            maxUsedExternalAddressIndex to maxWatchingExternalAddressIndex
        }

        validateIndexBounds(maxUsedIndex, maxWatchingIndex)

        val nextIndex = calculateNextIndex(maxUsedIndex, maxWatchingIndex)
        val derivedKeyPair = deriveValidKeyPair(nextIndex)

        updateMaxUsedIndexIfNeeded(maxUsedIndex, derivedKeyPair.lastLevelIndex)

        return createAddressGroup(derivedKeyPair)
    }

    private fun validateIndexBounds(maxUsedIndex: Int?, maxWatchingIndex: Int?) {
        require(maxUsedIndex == null || maxWatchingIndex != null) {
            "Max watching index must exist if max used index exists"
        }
        require(maxUsedIndex == null || maxUsedIndex <= maxWatchingIndex!!) {
            "Max used index ($maxUsedIndex) cannot exceed max watching index ($maxWatchingIndex)"
        }
    }

    private fun calculateNextIndex(maxUsedIndex: Int?, maxWatchingIndex: Int?): Int {
        return when {
            maxUsedIndex == null -> 0
            maxUsedIndex < maxWatchingIndex!! -> maxUsedIndex + 1
            else -> {
                val minUsable = maxWatchingIndex - Rules.EXTERNAL_ADDRESSES_WATCH_WINDOW_SIZE
                RandomGenerator.getInt(minUsable, maxWatchingIndex + 1)
            }
        }
    }

    private fun deriveValidKeyPair(nextIndex: Int): Schema.DerivedPublicKeyPair {
        return keysRepository.basePublicKeyPair
            .deriveFromAbsolutePath(Schema.getExternalKeyPath())
            .deriveNextValidChild(nextIndex)
            .also { keyPair ->
                Timber.tag(TAG).v("Derived key pair at index ${keyPair.lastLevelIndex}")
            }
    }

    private fun updateMaxUsedIndexIfNeeded(currentMax: Int?, newIndex: Int) {
        if (currentMax == null || newIndex > currentMax) {
            keysRepository.maxUsedExternalAddressIndex = newIndex
            Timber.tag(TAG).d("Updated max used index to $newIndex")
        }
    }

    private fun createAddressGroup(keyPair: Schema.DerivedPublicKeyPair): MuunAddressGroup {
        return MuunAddressGroup(
            v3 = LibwalletBridge.createAddressV3(keyPair, networkParameters),
            v4 = LibwalletBridge.createAddressV4(keyPair, networkParameters),
            v5 = LibwalletBridge.createAddressV5(keyPair, networkParameters)
        ).also {
            Timber.tag(TAG).d("Created address group with derivation index ${keyPair.lastLevelIndex}")
        }
    }
}

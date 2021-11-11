package io.muun.apollo.presentation.ui.show_qr.bitcoin

import android.os.Bundle
import icepick.State
import io.muun.apollo.data.external.Globals
import io.muun.apollo.domain.action.address.CreateAddressAction
import io.muun.apollo.domain.model.BitcoinAmount
import io.muun.apollo.domain.selector.BlockchainHeightSelector
import io.muun.apollo.domain.selector.CurrencyDisplayModeSelector
import io.muun.apollo.domain.selector.FeatureStatusSelector
import io.muun.apollo.presentation.analytics.AnalyticsEvent
import io.muun.apollo.presentation.ui.bundler.BitcoinAmountBundler
import io.muun.apollo.presentation.ui.show_qr.QrPresenter
import io.muun.common.bitcoinj.BitcoinUri
import io.muun.apollo.domain.model.MuunAddressGroup
import io.muun.apollo.domain.model.MuunFeature
import libwallet.Libwallet
import rx.Observable
import javax.inject.Inject

open class BitcoinAddressQrPresenter @Inject constructor(
    private val createAddress: CreateAddressAction,
    private val currencyDisplayModeSel: CurrencyDisplayModeSelector,
    private val blockchainHeightSel: BlockchainHeightSelector,
    private val featureStatusSel: FeatureStatusSelector
) : QrPresenter<BitcoinAddressView>() {

    @State
    lateinit var legacyAddress: String

    @State
    lateinit var segwitAddress: String

    @State
    lateinit var taprootAddress: String

    @State
    @JvmField
    var addressType: AddressType = AddressType.SEGWIT

    @State(BitcoinAmountBundler::class)
    @JvmField
    var amount: BitcoinAmount? = null

    // We need to state-save in presenter 'cause apparently this fragment being inside ViewPager
    // messes up our state saving/restoring for our custom views :'(
    @State
    @JvmField
    var showingAdvancedSettings = false

    override fun setUp(arguments: Bundle) {
        super.setUp(arguments)

        view.setCurrencyDisplayMode(currencyDisplayModeSel.get())
        view.setShowingAdvancedSettings(showingAdvancedSettings)


        // TODO: this should NOT be invoked using the `action()` method, but we can't invoke this
        // action from the main thread otherwise. What we should do is refactor our child presenters
        // to observe this result.
        try {
            onAddressesReady(createAddress.actionNow())
        } catch (error: Throwable) {
            handleError(error)
        }

        Observable.combineLatest(
            blockchainHeightSel.watchBlocksToTaproot(),
            featureStatusSel.watch(Libwallet.getUserActivatedFeatureTaproot()),
            view::setTaprootState
        ).let(this::subscribeTo)
    }

    override fun getQrContent(): String = if (amount != null) {
        BitcoinUri.convertToBitcoinUri(
            Globals.INSTANCE.network,
            getAddress(),
            amount!!.inSatoshis
        )
    } else {
        getAddress()
    }

    override fun getEntryEvent(): AnalyticsEvent {
        return AnalyticsEvent.S_RECEIVE(
            getTrackingParam(),
            parentPresenter.getOrigin()
        )
    }

    override fun hasLoadedCorrectly(): Boolean =
        ::legacyAddress.isInitialized

    override fun showFullContent() {
        view.showFullAddress(getAddress(), addressType)
    }

    private fun onAddressesReady(newAddresses: MuunAddressGroup) {
        if (!::legacyAddress.isInitialized) {
            legacyAddress = newAddresses.legacy.address
        }

        if (!::segwitAddress.isInitialized) {
            segwitAddress = newAddresses.segwit.address
        }

        if (!::taprootAddress.isInitialized) {
            taprootAddress = newAddresses.taproot.address
        }

        updateView()
    }

    fun switchAddressType(newAddressType: AddressType) {
        addressType = newAddressType
        updateView()
    }

    fun setAmount(bitcoinAmount: BitcoinAmount?) {
        this.amount = bitcoinAmount
        updateView()
    }

    fun toggleAdvancedSettings() {
        showingAdvancedSettings = !showingAdvancedSettings
    }

    private fun getAddress() =
        when (addressType) {
            AddressType.TAPROOT -> taprootAddress
            AddressType.SEGWIT -> segwitAddress
            AddressType.LEGACY -> legacyAddress
        }

    private fun updateView() {
        view.setContent(getQrContent(), addressType, amount?.inInputCurrency)
    }

    private fun getTrackingParam() =
        when (addressType) {
            AddressType.TAPROOT -> AnalyticsEvent.S_RECEIVE_TYPE.TAPROOT_ADDRESS
            AddressType.SEGWIT -> AnalyticsEvent.S_RECEIVE_TYPE.SEGWIT_ADDRESS
            AddressType.LEGACY -> AnalyticsEvent.S_RECEIVE_TYPE.LEGACY_ADDRESS
        }
}

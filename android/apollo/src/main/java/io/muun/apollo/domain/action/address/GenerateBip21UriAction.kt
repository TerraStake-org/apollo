package io.muun.apollo.domain.action.address

import io.muun.apollo.domain.action.base.BaseAsyncAction1
import io.muun.apollo.domain.action.incoming_swap.GenerateInvoiceAction
import io.muun.apollo.domain.libwallet.DecodedBitcoinUri
import io.muun.apollo.domain.libwallet.Invoice
import io.muun.apollo.domain.model.BitcoinAmount
import io.muun.apollo.domain.model.MuunAddressGroup
import org.bitcoinj.core.NetworkParameters
import rx.Observable
import timber.log.Timber
import javax.inject.Inject

class GenerateBip21UriAction @Inject constructor(
    private val networkParameters: NetworkParameters,
    private val generateInvoice: GenerateInvoiceAction,
    private val createAddress: CreateAddressAction
) : BaseAsyncAction1<BitcoinAmount?, DecodedBitcoinUri>() {

    companion object {
        private const val TAG = "GenerateBip21Uri"
    }

    override fun action(amount: BitcoinAmount?): Observable<DecodedBitcoinUri> {
        return Observable.zip(
            createAddress.action()
                .doOnNext { Timber.tag(TAG).d("Generated new address group") }
                .map(MuunAddressGroup::toAddressGroup),
            
            generateInvoice.action(amount?.inSatoshis)
                .doOnNext { Timber.tag(TAG).d("Generated invoice for amount: $amount") },
            
            { addressGroup, invoice ->
                try {
                    val decodedInvoice = Invoice.decodeInvoice(networkParameters, invoice)
                    Timber.tag(TAG).v("Successfully decoded invoice")
                    DecodedBitcoinUri(addressGroup, decodedInvoice, amount)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to decode invoice")
                    throw e
                }
            }
        ).doOnError { error ->
            Timber.tag(TAG).e(error, "Failed to generate BIP21 URI")
        }
    }
}

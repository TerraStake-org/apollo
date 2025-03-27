package io.muun.apollo.domain.action.address

import io.muun.apollo.domain.action.base.BaseAsyncAction1
import io.muun.apollo.domain.action.incoming_swap.GenerateInvoiceAction
import io.muun.apollo.domain.libwallet.DecodedBitcoinUri
import io.muun.apollo.domain.libwallet.Invoice
import io.muun.apollo.domain.model.BitcoinAmount
import io.muun.apollo.domain.model.MuunAddressGroup
import org.bitcoinj.core.NetworkParameters
import rx.Observable
import rx.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

class GenerateBip21UriAction @Inject constructor(
    private val networkParameters: NetworkParameters,
    private val generateInvoice: GenerateInvoiceAction,
    private val createAddress: CreateAddressAction
) : BaseAsyncAction1<BitcoinAmount?, DecodedBitcoinUri>() {

    companion object {
        private const val TAG = "GenerateBip21Uri"
        private const val INVOICE_DECODE_ERROR = "Invoice decode failed"
    }

    override fun action(amount: BitcoinAmount?): Observable<DecodedBitcoinUri> {
        return Observable.zip(
            getAddressGroup(),
            getInvoice(amount),
            ::createDecodedUri
        )
        .onErrorResumeNext { error ->
            Timber.tag(TAG).e(error, "BIP21 URI generation failed")
            Observable.error(error)
        }
        .subscribeOn(Schedulers.io())
    }

    private fun getAddressGroup(): Observable<MuunAddressGroup> {
        return createAddress.action()
            .doOnNext { Timber.tag(TAG).d("Address group generated") }
            .map(MuunAddressGroup::toAddressGroup)
    }

    private fun getInvoice(amount: BitcoinAmount?): Observable<String> {
        return generateInvoice.action(amount?.inSatoshis)
            .doOnNext { Timber.tag(TAG).d("Invoice generated for amount: ${amount?.inSatoshis} sat") }
    }

    private fun createDecodedUri(addressGroup: MuunAddressGroup, invoice: String): DecodedBitcoinUri {
        return try {
            val decodedInvoice = Invoice.decodeInvoice(networkParameters, invoice)
                ?: throw IllegalArgumentException("Null invoice returned from decoder")
            
            Timber.tag(TAG).v("Invoice successfully decoded")
            DecodedBitcoinUri(addressGroup, decodedInvoice, amount)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, INVOICE_DECODE_ERROR)
            throw InvoiceDecodeException(INVOICE_DECODE_ERROR, e)
        }
    }

    class InvoiceDecodeException(message: String, cause: Throwable) : Exception(message, cause)
}

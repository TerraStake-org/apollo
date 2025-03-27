package io.muun.apollo.domain.action.address;

import io.muun.apollo.domain.action.base.BaseAsyncAction1;
import io.muun.apollo.domain.action.incoming_swap.GenerateInvoiceAction;
import io.muun.apollo.domain.libwallet.DecodedBitcoinUri;
import io.muun.apollo.domain.libwallet.Invoice;
import io.muun.apollo.domain.model.BitcoinAmount;
import io.muun.apollo.domain.model.MuunAddressGroup;
import org.bitcoinj.core.NetworkParameters;
import rx.Observable;
import rx.schedulers.Schedulers;
import timber.log.Timber;

import javax.inject.Inject;

public class GenerateBip21UriAction extends BaseAsyncAction1<BitcoinAmount, DecodedBitcoinUri> {

    private static final String TAG = "GenerateBip21Uri";
    private static final String INVOICE_DECODE_ERROR = "Invoice decode failed";

    private final NetworkParameters networkParameters;
    private final GenerateInvoiceAction generateInvoice;
    private final CreateAddressAction createAddress;

    @Inject
    public GenerateBip21UriAction(
            NetworkParameters networkParameters,
            GenerateInvoiceAction generateInvoice,
            CreateAddressAction createAddress
    ) {
        this.networkParameters = networkParameters;
        this.generateInvoice = generateInvoice;
        this.createAddress = createAddress;
    }

    @Override
    public Observable<DecodedBitcoinUri> action(BitcoinAmount amount) {
        return Observable.zip(
                getAddressGroup(),
                getInvoice(amount),
                (addressGroup, invoice) -> createDecodedUri(addressGroup, invoice, amount)
        )
        .onErrorResumeNext(error -> {
            Timber.tag(TAG).e(error, "BIP21 URI generation failed");
            return Observable.error(error);
        })
        .subscribeOn(Schedulers.io());
    }

    private Observable<MuunAddressGroup> getAddressGroup() {
        return createAddress.action()
                .doOnNext(addressGroup -> Timber.tag(TAG).d("Address group generated"))
                .map(MuunAddressGroup::toAddressGroup);
    }

    private Observable<String> getInvoice(BitcoinAmount amount) {
        Long satoshis = amount != null ? amount.inSatoshis : null;
        return generateInvoice.action(satoshis)
                .doOnNext(invoice -> 
                    Timber.tag(TAG).d("Invoice generated for amount: %s sat", satoshis)
                );
    }

    private DecodedBitcoinUri createDecodedUri(
            MuunAddressGroup addressGroup, 
            String invoice,
            BitcoinAmount amount
    ) {
        try {
            Invoice decodedInvoice = Invoice.decodeInvoice(networkParameters, invoice);
            if (decodedInvoice == null) {
                throw new IllegalArgumentException("Null invoice returned from decoder");
            }
            
            Timber.tag(TAG).v("Invoice successfully decoded");
            return new DecodedBitcoinUri(addressGroup, decodedInvoice, amount);
        } catch (Exception e) {
            Timber.tag(TAG).e(e, INVOICE_DECODE_ERROR);
            throw new InvoiceDecodeException(INVOICE_DECODE_ERROR, e);
        }
    }

    public static class InvoiceDecodeException extends Exception {
        public InvoiceDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

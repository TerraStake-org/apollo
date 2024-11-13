package io.muun.apollo.presentation.ui.operation_detail;

import io.muun.apollo.R;
import io.muun.apollo.data.preferences.BlockchainHeightRepository;
import io.muun.apollo.domain.action.OperationActions;
import io.muun.apollo.domain.analytics.AnalyticsEvent;
import io.muun.apollo.domain.analytics.AnalyticsEvent.S_MORE_INFO_TYPE;
import io.muun.apollo.domain.model.BitcoinUnit;
import io.muun.apollo.domain.model.Operation;
import io.muun.apollo.domain.selector.BitcoinUnitSelector;
import io.muun.apollo.presentation.model.UiOperation;
import io.muun.apollo.presentation.ui.base.BasePresenter;
import io.muun.apollo.presentation.ui.base.di.PerActivity;
import io.muun.apollo.presentation.ui.utils.LinkBuilder;
import io.muun.common.Optional;

import android.os.Bundle;
import icepick.State;
import rx.Observable;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;

@PerActivity
public class OperationDetailPresenter extends BasePresenter<OperationDetailView> {

    public static final String OPERATION_ID_KEY = "OPERATION_ID";

    private final OperationActions operationActions;

    private final BitcoinUnitSelector bitcoinUnitSel;

    private final LinkBuilder linkBuilder;

    @State
    protected long operationId;

    @State
    protected BitcoinUnit bitcoinUnit;

    private final BlockchainHeightRepository blockchainHeightRepository;

    /**
     * Constructor.
     */
    @Inject
    public OperationDetailPresenter(
            OperationActions operationActions,
            BitcoinUnitSelector bitcoinUnitSel,
            LinkBuilder linkBuilder,
            BlockchainHeightRepository blockchainHeightRepository
    ) {

        this.operationActions = operationActions;
        this.bitcoinUnitSel = bitcoinUnitSel;
        this.linkBuilder = linkBuilder;
        this.blockchainHeightRepository = blockchainHeightRepository;
    }

    @Override
    public void setUp(@NotNull Bundle arguments) {
        super.setUp(arguments);

        final Optional<Long> maybeOperationId = takeLongArgument(arguments, OPERATION_ID_KEY);

        final boolean precondition = checkArgument(maybeOperationId.isPresent(), "operationId");

        if (precondition) {
            bitcoinUnit = bitcoinUnitSel.get();

            operationId = maybeOperationId.get();
            bindOperation();
        }
    }

    private void bindOperation() {
        final Observable<UiOperation> observable = operationActions
                .fetchOperationById(operationId)
                .doOnNext(this::reportOperationDetail)
                .map(op -> UiOperation.fromOperation(
                                op,
                                linkBuilder,
                                bitcoinUnit,
                                getContext()
                        )
                )
                .compose(getAsyncExecutor())
                .doOnNext(view::setOperation);

        subscribeTo(observable);
    }

    /**
     * Copy LN invoice to the clipboard.
     */
    public void copyLnInvoiceToClipboard(String invoice) {
        clipboardManager.copy("Lightning invoice", invoice);
    }

    /**
     * Copy swap preimage to the clipboard.
     */
    public void copySwapPreimageToClipboard(String preimage) {
        clipboardManager.copy("Swap preimage", preimage);
    }

    /**
     * Copy transaction id/hash to the clipboard.
     */
    public void copyTransactionIdToClipboard(String transactionId) {
        clipboardManager.copy("Transaction ID", transactionId);
    }

    /**
     * Copy receiving node to clipboard.
     */
    public void copyReceivingNodeToClipboard(String node) {
        clipboardManager.copy("Receiving Node", node);
    }

    /**
     * Copy fee amount to the clipboard.
     */
    public void copyNetworkFeeToClipboard(String fee) {
        clipboardManager.copy("Network fee", fee);
    }

    /**
     * Copy amount to the clipboard.
     */
    public void copyAmountToClipboard(String amount) {
        clipboardManager.copy("Amount", amount);
    }

    /**
     * Fire the SHARE intent with a given transaction ID.
     */
    public void shareTransactionId(String transactionId) {
        final String title = getContext().getString(R.string.operation_detail_share_txid_title);
        final String text = linkBuilder.rawTransactionLink(transactionId);

        navigator.shareText(getContext(), text, title);
    }

    public int getBlockchainHeight() {
        return blockchainHeightRepository.fetchLatest();
    }

    private void reportOperationDetail(Operation op) {
        analytics.report(new AnalyticsEvent.S_OPERATION_DETAIL((int) operationId, op.direction));
    }

    /**
     * Report analytics event of screen view event of Lightning "Confirmation Needed, why this?"
     * dialog.
     */
    public void reportShowConfirmationNeededInfo() {
        analytics.report(new AnalyticsEvent.S_MORE_INFO(S_MORE_INFO_TYPE.CONFIRMATION_NEEDED));
    }
}

package io.muun.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonInclude(JsonInclude.Include.NON_NULL)
public enum OperationStatus {

    /**
     * Newly created operation, without an associated transaction, or with an unsigned one.
     */
    CREATED,

    /**
     * Operation stored both in the server an on the signing client, in the process of being
     * signed.
     */
    @Deprecated // Long ago. Probably years. From the time we had a Transaction Draft
    SIGNING,

    /**
     * Operation with an associated transaction, which has been signed but not broadcasted yet.
     */
    @Deprecated // Long ago. Probably years. From the time we had a Transaction Draft
    SIGNED,

    /**
     * Operation with a transaction that's already been broadcasted, but hasn't confirmed yet.
     */
    BROADCASTED,

    /**
     * For a submarine swap Operation, the on-chain transaction was broadcasted and we're waiting
     * for off-chain payment to succeed.
     */
    SWAP_PENDING,

    /**
     * For a submarine swap Operation, the off-chain payment was started, but hasn't completed or
     * failed yet.
     */
    SWAP_ROUTING,

    /**
     * For a submarine swap Operation, the off-chain payment was successful, but the swap server has
     * not yet claimed the on-chain funds.
     */
    SWAP_PAYED,

    /**
     * For a submarine swap Operation, the off-chain payment was unsuccessful, and the on-chain
     * funds are time locked.
     */
    SWAP_FAILED,

    /**
     * For a submarine swap Operation, the off-chain payment has expired, and the on-chain funds
     * are the property of the sender again.
     */
    SWAP_EXPIRED,

    /**
     * For a submarine swap Operation, negotiating the channel open with the remote peer.
     */
    @Deprecated
    SWAP_OPENING_CHANNEL,

    /**
     * For a submarine swap Operation, waiting for a channel to be open in order to start
     * routing the payment.
     */
    @Deprecated
    SWAP_WAITING_CHANNEL,

    /**
     * Operation with its transaction present in a block (0 < confirmations < SETTLEMENT_NUMBER),
     * but not with enough transactions to be settled.
     * TODO: the semantics for this status are no longer accurate (for instance, fulfilled
     *  incoming swaps are CONFIRMED whenever the preimage is revealed, regardless of the
     *  confirmation status of any transaction).
     */
    CONFIRMED,

    /**
     * Operation with its transaction settled (confirmations >= SETTLEMENT_NUMBER).
     */
    SETTLED,

    /**
     * Operation with a transaction that hasn't been present in the last blockchain sync.
     */
    DROPPED,

    /**
     * Operation's transaction was rejected by the network.
     */
    FAILED;

    @JsonCreator
    public static OperationStatus fromValue(String value) {
        return OperationStatus.valueOf(value.toUpperCase());
    }

    @JsonValue
    public String toValue() {
        return this.name().toUpperCase();
    }
}

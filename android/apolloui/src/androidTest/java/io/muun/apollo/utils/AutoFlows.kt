package io.muun.apollo.utils

import android.content.Context
import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import io.muun.apollo.R
import io.muun.apollo.data.debug.LappClient
import io.muun.apollo.data.external.Gen
import io.muun.apollo.data.external.Globals
import io.muun.apollo.domain.libwallet.BitcoinUri
import io.muun.apollo.domain.model.AddressType
import io.muun.apollo.domain.model.user.UserPhoneNumber
import io.muun.apollo.presentation.ui.helper.isBtc
import io.muun.apollo.presentation.ui.utils.OS
import io.muun.apollo.presentation.ui.utils.UiUtils
import io.muun.apollo.utils.WithMuunInstrumentationHelpers.Companion.balanceNotEqualsErrorMessage
import io.muun.apollo.utils.screens.ReceiveScreen
import io.muun.common.model.DebtType
import io.muun.common.model.ReceiveFormatPreference
import io.muun.common.utils.BitcoinUtils
import io.muun.common.utils.LnInvoice
import io.muun.common.utils.Preconditions
import org.javamoney.moneta.Money
import javax.money.MonetaryAmount

class AutoFlows(
    override val device: UiDevice,
    override val context: Context,
) : WithMuunInstrumentationHelpers {

    fun signUp(pin: List<Int> = Gen.pin()) {

        signInScreen.startSignup()

        signUpFromPin(pin)
    }

    fun signUpFromPin(pin: List<Int>) {
        setupPinAndReachHome(pin, true)

        // Dismiss Welcome Dialog
        dismissDialog()
    }

    fun setupPinAndReachHome(pin: List<Int>, isNewUser: Boolean = false) {
        enterPin(pin) // choose pin
        enterPin(pin) // confirm pin

        if (isNewUser) {
            val result = homeScreen.waitForWelcomeDialog()

            if (!result) {
                throw IllegalStateException("Did not arrive at home (with welcome dialog) on time.")
            }

        } else {
            homeScreen.waitUntilVisible()
        }
    }

    fun enterPin(pin: List<Int>) {
        pin.forEach { id("key_$it").click() }
    }

    fun createRecoverableUser(pin: List<Int>, email: String, password: String) {
        signUp(pin)
        setUpEmailAndPassword(email, password)
    }

    fun setUpEmailAndPassword(email: String, password: String) {
        homeScreen.goToSecurityCenter()

        securityCenterScreen.goToEmailAndPassword()

        emailPasswordScreen.startSetup()

        emailPasswordScreen.enterEmailAndConfirm(email)

        emailPasswordScreen.awaitEmailVerification(email)

        emailPasswordScreen.enterPasswordsAndConfirm(password)

        emailPasswordScreen.acceptConditions()

        emailPasswordScreen.finishSetup()

        // We're at the Security Center, let's go back to the home
        backToHome()
    }

    fun skipEmailAndPasswordSetup() {
        homeScreen.goToSecurityCenter()

        securityCenterScreen.goToEmailAndPassword()

        emailPasswordScreen.skipSetup()

        backToHome()
    }

    fun setUpRecoveryCode(): List<String> {
        homeScreen.goToSecurityCenter()

        securityCenterScreen.goToRecoveryCode()

        val recoveryCode = recoveryCodeScreen.fillForm()

        backToHome()

        return recoveryCode
    }

    fun exportEmergencyKit(sniffActivationCode: () -> String) {
        homeScreen.goToSecurityCenter()

        securityCenterScreen.goToEmergencyKit()

        emergencyKitSetupScreen.doCompleteFlow {
            sniffActivationCode()
        }

        backToHome()
    }

    fun signIn(
        email: String = Gen.email(),
        password: String? = null,
        recoveryCodeParts: List<String>? = null,
        pin: List<Int> = Gen.pin(),
    ) {

        signInScreen.fillSignInForm(email, password, recoveryCodeParts)

        setupPinAndReachHome(pin)
    }

    fun rcSignIn(recoveryCode: List<String>, email: String? = null, pin: List<Int> = Gen.pin()) {

        signInScreen.rcSignIn(recoveryCode, email)
        setupPinAndReachHome(pin)
    }

    fun logOut() {
        homeScreen.goToSettings()

        scrollTo {

            // Log out.
            settingsScreen.logout()

            // Confirm log out on pop-up message.
            normalizedLabel(R.string.settings_logout_alert_yes).click()
        }
    }

    /**
     * Delete Wallet (some extra logic may apply, e.g u.u with positive balance may not
     * delete their wallets).
     */
    fun deleteWallet(isRecoverableUser: Boolean = false) {
        goToSettingsAndClickDeleteWallet()

        if (isRecoverableUser) {
            label(R.string.settings_delete_wallet_alert_body_recoverable_user).await()

        } else {
            label(R.string.settings_delete_wallet_alert_body_unrecoverable_user).await()
        }

        // Confirm on pop-up message.
        normalizedLabel(R.string.settings_delete_wallet_alert_yes).click()

        // Click Delete Success Continue Button
        normalizedLabel(R.string.delete_wallet_success_action).click()
    }

    /**
     * In some cases, we won't let them delete wallet if they can lose
     * money. This AutoFlow is what happens in those scenarios.
     */
    fun checkCannotDeleteWallet() {
        goToSettingsAndClickDeleteWallet()

        label(R.string.settings_delete_wallet_explanation_title).await()
        normalizedLabel(R.string.settings_delete_wallet_explanation_action).click()

        backToHome()
    }

    /**
     * Receive funds using Unified QR feature, using the LN invoice embedded.
     * Receive funds via Lightning Network using an AmountLess Invoice. TurboChannels flag hints
     * whether we should check for operation status pending or not.
     *
     * NOTE: BEWARE if amount is low (< debt limit) and turboChannels is disabled, this method will
     * fail as our Receive LN feature confirms the payment instantly (full debt mechanism).
     */
    fun receiveMoneyFromUnifiedQrViaLightning(
        amountInSat: Long,
        unifiedQrDraft: ReceiveScreen.UnifiedQrDraft,
    ) {
        val prevBalance = homeScreen.balanceInBtc

        val unifiedQr = unifiedQrDraft as ReceiveScreen.UnifiedQrDraft.OffChain
        Preconditions.checkArgument(
            unifiedQr.amountInSat == null || unifiedQr.amountInSat == amountInSat
        )

        // For fixed amount invoices, amount MUST NOT be specified, otherwise an error occurs.
        val amountToReceive = unifiedQr.amountInSat

        val invoice = getOwnInvoiceFromUnifiedQr(amountToReceive)

        LappClient().receiveBtcViaLN(invoice, amountInSat, unifiedQr.turboChannel)

        // Wait for balance to be updated:
        val amount = BitcoinUtils.satoshisToBitcoins(amountInSat)
        homeScreen.waitUntilBalanceEquals(prevBalance.add(amount))

        checkOperationDetails(amount, statusPending = !unifiedQr.turboChannel) {
            homeScreen.goToOperationDetail(0)
        }
    }

    private fun getOwnInvoiceFromUnifiedQr(amountInSat: Long? = null): String {
        homeScreen.goToReceive()

        if (amountInSat != null) {
            receiveScreen.addUnifiedQrAmount(amountInSat)
        }

        val unifiedQr = receiveScreen.unifiedQr

        device.pressBack() // Back to Home
        return BitcoinUri.parse(unifiedQr).third
    }

    /**
     * Receive funds via Lightning Network using an AmountLess Invoice. TurboChannels flag hints
     * whether we should check for operation status pending or not.
     *
     * NOTE: BEWARE if amount is low (< debt limit) and turboChannels is disabled, this method will
     * fail as our Receive LN feature confirms the payment instantly (full debt mechanism).
     */
    fun receiveMoneyFromLNWithAmountLessInvoice(amountInSats: Long, turboChannels: Boolean = true) {
        receiveMoneyFromLN(amountInSats, turboChannels, false)
    }

    /**
     * Receive funds via Lightning Network using an Invoice with amount. TurboChannels flag hints
     * whether we should check for operation status pending or not.
     *
     * NOTE: BEWARE if amount is low (< debt limit) and turboChannels is disabled, this method will
     * fail as our Receive LN feature confirms the payment instantly (full debt mechanism).
     */
    fun receiveMoneyFromLNWithInvoiceWithAmount(amountInSats: Long, turboChannels: Boolean = true) {
        receiveMoneyFromLN(amountInSats, turboChannels, true)
    }

    private fun receiveMoneyFromLN(amountInSat: Long, turboChannel: Boolean, amountFixed: Boolean) {
        val prevBalance = homeScreen.balanceInBtc

        // For fixed amount invoices, amount MUST NOT be specified, otherwise an error occurs.
        val amountToReceive = if (amountFixed) {
            null
        } else {
            amountInSat
        }

        val invoice = getOwnInvoice(amountInSat)

        LappClient().receiveBtcViaLN(invoice, amountToReceive, turboChannel)

        // Wait for balance to be updated:
        val amount = BitcoinUtils.satoshisToBitcoins(amountInSat)
        homeScreen.waitUntilBalanceEquals(prevBalance.add(amount))

        checkOperationDetails(amount, statusPending = !turboChannel) {
            homeScreen.goToOperationDetail(0)
        }
    }

    fun getOwnInvoice(amountInSat: Long? = null): String {
        homeScreen.goToReceive()

        if (amountInSat != null) {
            receiveScreen.addInvoiceAmount(amountInSat)
        }

        val invoice = receiveScreen.invoice
        device.pressBack() // Back to Home
        return invoice
    }

    fun receiveMoneyFromNetworkViaBitcoinUri(
        amount: MonetaryAmount,
        addressType: AddressType = AddressType.SEGWIT,
    ) = try {
        tryReceiveMoneyFromNetworkViaBitcoinUri(amount, addressType)
    } catch (e: AssertionError) {
        if (e.message != null && e.message!!.contains(balanceNotEqualsErrorMessage)) {

            LappClient().generateBlocks(30) // we don't want to need this again soon
            Thread.sleep(2000)
            tryReceiveMoneyFromNetwork(amount)
        } else {
            throw e
        }
    }

    private fun tryReceiveMoneyFromNetworkViaBitcoinUri(
        amount: MonetaryAmount,
        addressType: AddressType = AddressType.SEGWIT,
    ) {
        val expectedBalance = homeScreen.balanceInBtc
        val balanceAfter = expectedBalance.add(amount)

        // Generate a Bitcoin Uri with amount:
        val rawClipboard = getOwnBitcoinUri(amount, addressType)
        val bitcoinUri = io.muun.common.bitcoinj.BitcoinUri(Globals.INSTANCE.network, rawClipboard)

        val amountInBtc = BitcoinUtils.satoshisToBitcoins(bitcoinUri.amount.value)
        assertMoneyEqualsWithRoundingHack(amountInBtc, amount)

        // Hit RegTest to receive money from the network:
        LappClient().receiveBtc(amountInBtc.number.toDouble(), bitcoinUri.address!!)

        // Wait for balance to be updated:
        homeScreen.waitUntilBalanceEquals(balanceAfter)
    }

    private fun getOwnBitcoinUri(amount: MonetaryAmount, addressType: AddressType): String {
        homeScreen.goToReceive()

        receiveScreen.addBitcoinUriAmount(amount)
        receiveScreen.selectAddressType(addressType)

        val bitcoinUri = receiveScreen.bitcoinUri
        device.pressBack() // Back to Home
        return bitcoinUri
    }

    fun receiveMoneyFromNetwork(amount: MonetaryAmount) = try {
        tryReceiveMoneyFromNetwork(amount)
    } catch (e: AssertionError) {
        if (e.message != null && e.message!!.contains(balanceNotEqualsErrorMessage)) {

            LappClient().generateBlocks(30) // we don't want to need this again soon
            Thread.sleep(2000)
            tryReceiveMoneyFromNetwork(amount)
        } else {
            throw e
        }
    }

    private fun tryReceiveMoneyFromNetwork(amount: MonetaryAmount) {
        val expectedBalance = homeScreen.balanceInBtc
        val balanceAfter = expectedBalance.add(amount)

        // Generate an address:
        val address = getOwnAddress()

        // Hit RegTest to receive money from the network:
        LappClient().receiveBtc(amount.number.toDouble(), address)

        // Wait for balance to be updated:
        homeScreen.waitUntilBalanceEquals(balanceAfter)
    }

    fun getOwnAddress(): String {
        homeScreen.goToReceive()
        val address = receiveScreen.address
        device.pressBack() // Back to Home
        return address
    }

    fun setupP2P(
        phoneNumber: UserPhoneNumber = Gen.userPhoneNumber(),
        verificationCode: String = Gen.numeric(6),
        firstName: String = Gen.alpha(5),
        lastName: String = Gen.alpha(5),
    ) {

        homeScreen.goToP2PSetup()
        p2pScreen.fillForm(phoneNumber, verificationCode, firstName, lastName)
    }

    fun startOperationManualInputTo(destination: String) {
        homeScreen.goToSend()
        uriInput(R.id.uri_input).text = destination

        if (destination == ReceiveScreen.lastCopiedFromClipboard) {
            labelWith(R.string.send_cyclic_payment_warning).assertExists()

        } else {
            labelWith(R.string.send_cyclic_payment_warning).assertDoesntExist()
        }

        muunButton(R.id.confirm).press()
    }

    fun startOperationFromClipboardTo(destination: String) {
        Clipboard.write(destination)

        homeScreen.goToSend()

        if (OS.supportsClipboardAccessNotification()) {
            muunButton(R.id.paste_button).press()
            muunButton(R.id.confirm).press()

        } else {
            uriPaster.waitForExists().click()
        }
    }

    fun sendToAddressFromClipboard(money: Money, receivingAddress: String, description: String) {
        newOperation(money, description) {
            startOperationFromClipboardTo(receivingAddress)
        }
    }

    /**
     * AutoFlow to make an Operation using ALL FUNDS using the default way to reach new operation.
     * After submitting it, goes to OperationDetail screen to perform adequate checks. Leaves you at
     * home screen, with the operation PENDING.
     * <p>
     * To confirm or settle operation see settleOperation AutoFlow.
     */
    fun spendAllFunds(descriptionToEnter: String? = null) {

        val receivingAddress = "2N2y9wGHh7AfqwQ8dk5cQfhjvEAAq6xhjb6"

        spendAllFunds(descriptionToEnter) {
            startOperationFromClipboardTo(receivingAddress)
        }
    }

    /**
     * AutoFlow to make an Operation using ALL FUNDS. After submitting it, goes to OperationDetail
     * screen to perform adequate checks. Leaves you at home screen, with the operation PENDING.
     * <p>
     * To confirm or settle operation see settleOperation AutoFlow.
     */
    private fun spendAllFunds(descriptionToEnter: String? = null, reachNewOperation: () -> Unit) {

        val balanceBefore = homeScreen.balanceInBtc

        reachNewOperation()

        newOpScreen.waitUntilVisible()
        newOpScreen.fillFormUsingAllFunds(descriptionToEnter)

        // Ensure amounts in BTC (needed for post submit checks in balance and opDetail)
        if (!newOpScreen.confirmedAmount.currency.isBtc()) {
            newOpScreen.rotateAmountCurrencies()
        }

        // Keep these to check later:
        val amount = newOpScreen.confirmedAmount
        val fee = newOpScreen.confirmedFee
        val description = newOpScreen.confirmedDescription
        val total = newOpScreen.confirmedTotal
        newOpScreen.submit()

        homeScreen.waitUntilBalanceCloseTo(balanceBefore.subtract(total))

        checkOperationDetails(amount, description, fee) {
            homeScreen.goToOperationDetail(description, isPending = true)
        }
    }

    /**
     * AutoFlow to make an Operation. After submitting it, goes to OperationDetail screen to
     * perform adequate checks. Leaves you at home screen, with the operation PENDING.
     * <p>
     * To confirm or settle operation see settleOperation auto flow.
     */
    fun newOperation(
        amountToEnter: MonetaryAmount,
        descriptionToEnter: String,
        reachNewOperation: () -> Unit,
    ) {

        val balanceBefore = homeScreen.balanceInBtc

        reachNewOperation()

        newOpScreen.waitUntilVisible()
        newOpScreen.fillForm(amountToEnter, descriptionToEnter)

        // Keep these to check later:
        val destination = newOpScreen.destination
        val amount = newOpScreen.confirmedAmount
        val fee = newOpScreen.confirmedFee
        val description = newOpScreen.confirmedDescription
        val total = newOpScreen.confirmedTotal
        newOpScreen.submit()

        checkBalanceAfterOnchainOperation(destination, balanceBefore, fee, total)

        checkOperationDetails(amount, description, fee) {
            homeScreen.goToOperationDetail(description, isPending = true)
        }
    }

    fun settleOperation(description: String) {

        // Settle the transaction:
        generateBlocksAndWaitForUpdate(6)

        homeScreen.goToOperationDetail(description) // will fail if still pending
        opDetailScreen.waitForStatusChange(context.getString(R.string.operation_completed))

        exitOpDetailAndReturnHome()
    }

    /**
     * AutoFlow to make a SubmarineSwap, using the default way to reach new operation and
     * performing default checks operationDetail screen. Leaves you at home screen.
     */
    fun newSubmarineSwap(amountInSat: Int, debtType: DebtType = DebtType.NONE) {

        val invoice = LappClient().getLnInvoice(amountInSat)
        val desc = "This is a note " + System.currentTimeMillis()

        newSubmarineSwap(invoice, desc, debtType) {
            startOperationFromClipboardTo(invoice.original)
        }

        checkSubmarineSwapSuccess()
    }

    /**
     * AutoFlow to make a SubmarineSwap. Leaves you at OperationDetail screen, so caller can perform
     * adequate checks.
     */
    fun newSubmarineSwap(
        invoice: LnInvoice,
        descriptionToEnter: String,
        debtType: DebtType = DebtType.NONE,
        reachNewOperation: () -> Unit,
    ) {

        val balanceBefore = homeScreen.balanceInBtc

        reachNewOperation()

        newOpScreen.waitUntilVisible()
        newOpScreen.fillForm(invoice, descriptionToEnter, debtType)

        // Keep these to check later:
        val amount = newOpScreen.confirmedAmount
        val description = newOpScreen.confirmedDescription
        val lightningFee = newOpScreen.confirmedFee
        val total = newOpScreen.confirmedTotal

        newOpScreen.submit()

        homeScreen.waitUntilBalanceCloseTo(balanceBefore.subtract(total))

        // Account for the special case where swap is completed REALLY fast

        homeScreen.goToOperationDetailContaining(description) // don't care about status

        opDetailScreen.checkStatus(
            context.getString(R.string.operation_pending),
            context.getString(R.string.operation_completed)
        )

        opDetailScreen.checkAmount(amount)
        opDetailScreen.checkDescription(description)

        lightningFee.let {
            opDetailScreen.checkLightningFee(it)
        }

        opDetailScreen.checkInvoice(invoice.original)
    }

    /**
     * Perform submarine swap checks in success scenarios. By default, assumes we are in
     * Operation Detail screen.
     */
    private fun checkSubmarineSwapSuccess(
        is0Conf: Boolean = true,
        reachOperationDetail: () -> Unit = {},
    ) {

        reachOperationDetail()

        if (is0Conf) {

            checkSwapConfirmed()

        } else {
            // Confirm the swap:
            LappClient().generateBlocks(1)
            checkSwapConfirmed()

        }

        exitOpDetailAndReturnHome()
    }

    fun checkSubmarineSwapFail(reachOperationDetail: () -> Unit = {}) {
        reachOperationDetail()
        opDetailScreen.waitForStatusChange(R.string.operation_failed)
        // TODO more?

        exitOpDetailAndReturnHome()
    }

    private fun checkSwapConfirmed() {
        opDetailScreen.waitForStatusChange(R.string.operation_completed)
        // TODO check receiving node?
    }

    fun tryAllFeesAndExit() {

        // Try medium fee:
        newOpScreen.goToEditFee()
        val optionMedium = recomFeeScreen.selectFeeOptionMedium()
        recomFeeScreen.confirmFee()

        newOpScreen.checkConfirmedData(fee = optionMedium)

        // Try slow fee (TODO we should control whether it appears, instead of asking):
        newOpScreen.goToEditFee()
        if (recomFeeScreen.hasFeeOptionSlow()) {
            val optionSlow = recomFeeScreen.selectFeeOptionSlow()
            recomFeeScreen.confirmFee()

            newOpScreen.checkConfirmedData(fee = optionSlow)
        } else {
            device.pressBack()
        }

        // Try fast fee:
        newOpScreen.goToEditFee()
        val optionFast = recomFeeScreen.selectFeeOptionFast()
        recomFeeScreen.confirmFee()

        newOpScreen.checkConfirmedData(fee = optionFast)

        // Try manual fee:
        newOpScreen.goToEditFee()
        recomFeeScreen.goToManualFee()
        val optionManual = manualFeeScreen.editFeeRate(optionMedium.feeRate + 1)
        manualFeeScreen.confirmFeeRate()

        // TODO test mempool congested?

        newOpScreen.checkConfirmedData(fee = optionManual)

        // Abort operation like a boss:
        device.pressBack()
        device.pressBack()
        device.pressBack()
    }

    fun changePassword(user: RandomUser, newPassword: String) {

        homeScreen.goToSettings()
        settingsScreen.goToChangePassword()

        changePasswordScreen.fillForm(user, newPassword)

        backToHome()
    }

    fun toggleTurboChannels() {
        homeScreen.goToSettings()

        settingsScreen.toggleTurboChannels()

        backToHome()
    }

    fun turnOnReceiveLightningByDefault() {
        homeScreen.goToSettings()

        settingsScreen.turnOnReceiveLightningByDefault()
        backToHome()
    }

    fun turnOnReceiveBitcoinByDefault() {
        homeScreen.goToSettings()

        settingsScreen.turnOnReceiveBitcoinByDefault()
        backToHome()
    }

    fun turnOnUnifiedQr() {
        homeScreen.goToSettings()

        settingsScreen.turnOnUnifiedQr()
        backToHome()
    }

    fun checkReceivePreferenceIs(receiveFormatPreference: ReceiveFormatPreference) {
        homeScreen.goToReceive()
        receiveScreen.checkReceivePreferenceIs(receiveFormatPreference)
        device.pressBack()
    }

    fun setBitcoinUnitToSat() {
        homeScreen.goToSettings()

        settingsScreen.setBitcoinUnitToSat()

        backToHome()
    }

    fun checkOperationDetails(
        amount: MonetaryAmount,
        description: String? = null,
        fee: MonetaryAmount? = null,
        statusPending: Boolean = true,
        reachOperationDetail: () -> Unit,
    ) {

        reachOperationDetail()

        if (statusPending) {
            opDetailScreen.checkStatus(context.getString(R.string.operation_pending))
        } else {
            opDetailScreen.waitForStatusChange(R.string.operation_completed)
        }

        opDetailScreen.checkAmount(amount)

        if (fee != null) {
            opDetailScreen.checkFee(fee)
        }

        if (description != null) {
            opDetailScreen.checkDescription(description)
        }

        exitOpDetailAndReturnHome()
    }

    fun lnUrlWithdrawViaReceive(seenLnurlFirstTime: Boolean = false) {
        val lnurl = LappClient().generateWithdrawLnUrl()
        Clipboard.write(lnurl)

        homeScreen.goToReceive()
        receiveScreen.goToScanLnUrl()

        if (!seenLnurlFirstTime) {
            pressMuunButton(R.id.lnurl_intro_action)
        }

        if (OS.supportsClipboardAccessNotification()) {
            id(R.id.paste_from_clipboard).click()

        } else {
            uriPaster.waitForExists().click()
        }

        // Let's wait a sec until withdraw succeeds
        SystemClock.sleep(10000)
    }

    fun lnUrlWithdrawViaSend(
        variant: LappClient.LnUrlVariant = LappClient.LnUrlVariant.NORMAL,
        submitLnurl: (String) -> Unit = ::startOperationFromClipboardTo,
    ) {

        startLnUrlWithdrawViaSend(variant, submitLnurl)

        if (variant == LappClient.LnUrlVariant.SLOW) {

            // Let's wait for taking too long state (+15 secs)
            SystemClock.sleep(16_000)

            muunButton(R.id.lnurl_withdraw_action).waitForExists()
                .textEquals(MuunTexts.normalize(R.string.error_op_action))
                .press()
        } else {
            // Let's wait a sec until withdraw succeeds
            SystemClock.sleep(10000)
        }
    }

    fun startLnUrlWithdrawViaSend(
        variant: LappClient.LnUrlVariant,
        submitLnurl: (String) -> Unit = ::startOperationFromClipboardTo,
    ) {
        val lnurl = LappClient().generateWithdrawLnUrl(variant)
        println("Using lnurl: $lnurl")

        submitLnurl(lnurl)

        pressMuunButton(R.id.lnurl_withdraw_confirm_action)
    }

    fun signUpUserWithExistingUserAsContact(contact: RandomUser) {
        grantPermission("android.permission.READ_CONTACTS")

        // Sign up the future contact, set up P2P and logout:

        signUp()
        setupP2P(
            phoneNumber = contact.phoneNumber,
            firstName = contact.firstName,
            lastName = contact.lastName
        )

        deleteWallet()

        // Sign up ourselves:
        signUp()
        setupP2P()

        // Create the system contact:
        SystemContacts.create(Gen.alpha(8), contact.phoneNumber.toE164String())
    }

    // PRIVATE, helper stuff

    private fun checkBalanceAfterOnchainOperation(
        destination: String,
        balanceBefore: Money,
        fee: MonetaryAmount,
        total: MonetaryAmount,
    ) {

        val lastCopiedFromClipboard = ReceiveScreen.lastCopiedFromClipboard
        val expectedBalance = if (destination == UiUtils.ellipsize(lastCopiedFromClipboard)) {
            // Its a cyclic payment! We just subtract the fee
            balanceBefore.subtract(fee)
        } else {
            balanceBefore.subtract(total)
        }

        homeScreen.waitUntilBalanceCloseTo(expectedBalance)
    }

    private fun goToSettingsAndClickDeleteWallet() {
        homeScreen.goToSettings()

        scrollTo {
            settingsScreen.deleteWallet()
        }
    }

    private fun exitOpDetailAndReturnHome() {
        device.pressBack()

        checkScreenShows(R.string.home_operations_list_title)

        device.pressBack()
    }

    private fun backToHome() {
        id(R.id.home_fragment).click()
    }

    private fun dismissDialog() {
        device.pressBack() // Pressing back should dismiss dialog ;)
    }
}

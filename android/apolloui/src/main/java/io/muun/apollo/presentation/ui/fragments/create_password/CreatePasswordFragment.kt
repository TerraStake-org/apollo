package io.muun.apollo.presentation.ui.fragments.create_password

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import io.muun.apollo.R
import io.muun.apollo.databinding.FragmentCreatePasswordBinding
import io.muun.apollo.domain.errors.UserFacingError
import io.muun.apollo.presentation.ui.base.SingleFragment
import io.muun.apollo.presentation.ui.view.MuunButton
import io.muun.apollo.presentation.ui.view.MuunHeader
import io.muun.apollo.presentation.ui.view.MuunTextInput

class CreatePasswordFragment : SingleFragment<CreatePasswordPresenter>(), CreatePasswordView {

    private val binding: FragmentCreatePasswordBinding
        get() = getBinding() as FragmentCreatePasswordBinding

    private val passwordInput: MuunTextInput
        get() = binding.createPasswordInput

    private val passwordConfirmInput: MuunTextInput
        get() = binding.createPasswordConfirmInput

    private val confirmButton: MuunButton
        get() = binding.createPasswordConfirm

    override fun inject() {
        component.inject(this)
    }

    override fun getLayoutResource() =
        R.layout.fragment_create_password

    override fun bindingInflater(): (LayoutInflater, ViewGroup, Boolean) -> ViewBinding {
        return FragmentCreatePasswordBinding::inflate
    }

    override fun initializeUi(view: View) {
        passwordInput.setPasswordRevealEnabled(true)
        passwordInput.setOnChangeListener(this) {
            validatePassword()
        }

        passwordConfirmInput.setPasswordRevealEnabled(true)
        passwordConfirmInput.setOnChangeListener(this) {
            validatePassword()
        }

        confirmButton.isEnabled = false
        confirmButton.setOnClickListener {
            presenter.submitPassword(
                passwordInput.text.toString(),
                passwordConfirmInput.text.toString()
            )
        }
    }

    override fun setUpHeader() {
        // Parent Activity has already taken care of the rest
        parentActivity.header.setNavigation(MuunHeader.Navigation.EXIT)
    }

    override fun onBackPressed(): Boolean {
        presenter.goBack()
        return true
    }

    override fun onResume() {
        super.onResume()
        passwordInput.requestFocusInput()
    }

    override fun setPasswordError(error: UserFacingError?) {
        passwordInput.clearError()

        if (error != null) {
            passwordInput.setError(error)
            passwordInput.requestFocusInput()
        }
    }

    override fun setConfirmPasswordError(error: UserFacingError) {
        passwordConfirmInput.clearError()

        passwordConfirmInput.setError(error)
        passwordConfirmInput.requestFocusInput()
        confirmButton.isEnabled = false
    }

    override fun setLoading(isLoading: Boolean) {
        passwordInput.isEnabled = !isLoading
        confirmButton.setLoading(isLoading)
    }

    private fun validatePassword() {
        val validPassword = presenter.isValidPassword(passwordInput.text.toString())
        val validPasswordConfirm = presenter.isValidPassword(passwordConfirmInput.text.toString())

        confirmButton.isEnabled = validPassword && validPasswordConfirm
    }
}
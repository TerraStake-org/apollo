package io.muun.apollo.presentation.ui.fragments.create_email_help

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewbinding.ViewBinding
import io.muun.apollo.R
import io.muun.apollo.databinding.FragmentCreateEmailHelpBinding
import io.muun.apollo.presentation.ui.base.SingleFragment
import io.muun.apollo.presentation.ui.utils.StyledStringRes
import io.muun.apollo.presentation.ui.view.MuunHeader

class CreateEmailHelpFragment : SingleFragment<CreateEmailHelpPresenter>() {

    private val binding: FragmentCreateEmailHelpBinding
        get() = getBinding() as FragmentCreateEmailHelpBinding

    private val textView: TextView
        get() = binding.text

    override fun inject() =
        component.inject(this)

    override fun getLayoutResource() =
        R.layout.fragment_create_email_help

    override fun bindingInflater(): (LayoutInflater, ViewGroup, Boolean) -> ViewBinding {
        return FragmentCreateEmailHelpBinding::inflate
    }

    override fun initializeUi(view: View) {
        val styledRes = StyledStringRes(
            requireContext(),
            R.string.create_email_help_content,
            this::onLinkClick
        )

        textView.text = styledRes.toCharSequence()
    }

    override fun setUpHeader() {
        parentActivity.header.let {
            it.setNavigation(MuunHeader.Navigation.BACK)
            it.hideTitle()
            it.setIndicatorText(null)
            it.setElevated(false)
        }
    }

    private fun onLinkClick(id: String) {
        presenter.goToSupportEmail()
    }
}
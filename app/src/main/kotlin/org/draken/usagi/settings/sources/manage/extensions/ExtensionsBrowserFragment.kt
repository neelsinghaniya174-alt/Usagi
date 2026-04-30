package org.draken.usagi.settings.sources.manage.extensions

import android.os.Bundle
import android.view.View
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.draken.usagi.R
import org.draken.usagi.databinding.FragmentExtensionsBrowserBinding

@AndroidEntryPoint
class ExtensionsBrowserFragment : Fragment(R.layout.fragment_extensions_browser) {

	private val viewModel: ExtensionsBrowserViewModel by viewModels()
	private lateinit var binding: FragmentExtensionsBrowserBinding
	private lateinit var adapter: ExtensionsAdapter

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		binding = FragmentExtensionsBrowserBinding.bind(view)

		adapter = ExtensionsAdapter { extension, action ->
			Log.d("Keiyoushi", "Action $action for ${extension.name}")
			when (action) {
				ExtensionAction.INSTALL -> viewModel.installExtension(extension)
				ExtensionAction.UPDATE -> viewModel.updateExtension(extension)
				ExtensionAction.UNINSTALL -> viewModel.uninstallExtension(extension)
			}
		}

		binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
		binding.recyclerView.adapter = adapter

		viewModel.extensions.observe(viewLifecycleOwner) { list ->
			adapter.submitList(list)
		}

		viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
			binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
		}

		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.loadExtensions()
		}
	}
}

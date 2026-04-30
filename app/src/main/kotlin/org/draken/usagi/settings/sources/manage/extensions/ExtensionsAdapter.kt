package org.draken.usagi.settings.sources.manage.extensions

import android.util.Log
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.core.plugin.model.KeiyoushiExtension
import org.draken.usagi.databinding.ItemExtensionBinding
import android.view.LayoutInflater

class ExtensionsAdapter(
	private val onAction: (KeiyoushiExtension, ExtensionAction) -> Unit
) : ListAdapter<ExtensionItem, ExtensionsAdapter.ViewHolder>(DiffCallback) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val binding = ItemExtensionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	inner class ViewHolder(private val binding: ItemExtensionBinding) :
		RecyclerView.ViewHolder(binding.root) {

		fun bind(item: ExtensionItem) {
			binding.textName.text = item.remote.name
			binding.textLang.text = item.remote.lang
			binding.textVersion.text = item.remote.version

			val installed = item.installed
			val updateAvailable = installed && (item.installedVersion ?: 0) < item.remote.code

			binding.buttonInstall.text = when {
				!installed -> "Install"
				updateAvailable -> "Update"
				else -> "Installed"
			}
			binding.buttonInstall.isEnabled = !installed || updateAvailable

			binding.buttonInstall.setOnClickListener {
				Log.d("Keiyoushi", "Button install clicked for ${item.remote.name}")
				val action = if (!installed) ExtensionAction.INSTALL else ExtensionAction.UPDATE
				onAction(item.remote, action)
			}

			binding.buttonUninstall.isVisible = installed
			binding.buttonUninstall.setOnClickListener {
				Log.d("Keiyoushi", "Uninstall button clicked for ${item.remote.name}")
				onAction(item.remote, ExtensionAction.UNINSTALL)
			}
		}
	}

	object DiffCallback : DiffUtil.ItemCallback<ExtensionItem>() {
		override fun areItemsTheSame(oldItem: ExtensionItem, newItem: ExtensionItem): Boolean =
			oldItem.remote.pkg == newItem.remote.pkg

		override fun areContentsTheSame(oldItem: ExtensionItem, newItem: ExtensionItem): Boolean =
			oldItem == newItem
	}
}

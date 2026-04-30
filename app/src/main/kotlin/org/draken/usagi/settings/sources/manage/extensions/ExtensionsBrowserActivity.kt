package org.draken.usagi.settings.sources.manage.extensions

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R

@AndroidEntryPoint
class ExtensionsBrowserActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_extensions_browser)

		// Check if the fragment is already added to avoid unnecessary transactions.
		if (savedInstanceState == null) {
			val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
			if (fragment == null) {
				// This method is safe for StrictMode and ensures the transaction is executed immediately.
				supportFragmentManager.beginTransaction()
					.replace(R.id.fragment_container, ExtensionsBrowserFragment())
					.commitNow()
			}
		}
	}
}

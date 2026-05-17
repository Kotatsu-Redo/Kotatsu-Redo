package org.koitharu.kotatsu.core.os

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat

// https://stackoverflow.com/questions/77555641/saf-no-activity-found-to-handle-intent-android-intent-action-open-document-tr
class OpenDocumentTreeHelper(
	activityResultCaller: ActivityResultCaller,
	flags: Int,
	callback: ActivityResultCallback<Uri?>
) : ActivityResultLauncher<Uri?>() {

	constructor(activityResultCaller: ActivityResultCaller, callback: ActivityResultCallback<Uri?>) : this(
		activityResultCaller,
		0,
		callback,
	)

	private val pickFileTreeLauncherPrimaryStorage = activityResultCaller.registerForActivityResult(
		OpenDocumentTreeContractPrimaryStorage(flags),
		callback
	)
	private val pickFileTreeLauncherDefault = activityResultCaller.registerForActivityResult(
		contract = OpenDocumentTreeContractDefault(flags),
		callback = callback,
	)

	override fun launch(input: Uri?, options: ActivityOptionsCompat?) {
		try {
			pickFileTreeLauncherDefault.launch(input, options)
		} catch (e: Exception) {
			try {
				pickFileTreeLauncherPrimaryStorage.launch(input, options)
			} catch (e2: Exception) {
				e.addSuppressed(e2)
				throw e
			}
		}
	}

	override fun unregister() {
		pickFileTreeLauncherPrimaryStorage.unregister()
		pickFileTreeLauncherDefault.unregister()
	}

	override val contract: ActivityResultContract<Uri?, *>
		get() = pickFileTreeLauncherPrimaryStorage.contract

	private open class OpenDocumentTreeContractDefault(
		private val flags: Int,
	) : ActivityResultContracts.OpenDocumentTree() {

		override fun createIntent(context: Context, input: Uri?): Intent {
			val intent = super.createIntent(context, input)
			intent.addFlags(flags)
			return intent
		}
	}

	private class OpenDocumentTreeContractPrimaryStorage(
		private val flags: Int,
	) : OpenDocumentTreeContractDefault(flags) {

		override fun createIntent(context: Context, input: Uri?): Intent {
			val intent = (context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager)
				?.primaryStorageVolume
				?.createOpenDocumentTreeIntent()
			if (intent == null) { // fallback
				return super.createIntent(context, input)
			}
			intent.addFlags(flags)
			if (input != null) {
				intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
			}
			return intent
		}
	}
}

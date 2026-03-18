package org.koitharu.kotatsu.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.appUrl
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File

private const val TYPE_TEXT = "text/plain"
private const val TYPE_IMAGE = "image/*"
private const val TYPE_CBZ = "application/x-cbz"

// Deprecated ShareHelper rimosso. Usare direttamente ShareCompat.IntentBuilder nei call-site.

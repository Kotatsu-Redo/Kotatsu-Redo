package org.koitharu.kotatsu.sourcescore.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class CommunityHttpClient

/**
 * Kept in its own module rather than added to `core/AppModule.kt`, so the whole feature is one
 * package that can be reviewed - or reverted - without touching upstream files.
 */
@Module
@InstallIn(SingletonComponent::class)
object SourceScoreModule {

	@Provides
	@Singleton
	fun provideCommunityDatabase(
		@ApplicationContext context: Context,
	): CommunityDatabase = CommunityDatabase(context)

	/**
	 * Built from the base client so it inherits proxy and DNS settings, but deliberately **without**
	 * the manga-source interceptors: no CloudFlare handling, no cookie jar, no image proxy. This
	 * talks to one known server over TLS, and every one of those interceptors exists to cope with
	 * hostile scraping targets that this is not.
	 *
	 * Short timeouts on purpose. Every call here is best-effort and must never make the app feel
	 * slow - if the community server is struggling, ranking quietly falls back to cached scores.
	 */
	@Provides
	@Singleton
	@CommunityHttpClient
	fun provideCommunityHttpClient(
		@BaseHttpClient baseClient: OkHttpClient,
	): OkHttpClient = baseClient.newBuilder()
		.connectTimeout(10, TimeUnit.SECONDS)
		.readTimeout(20, TimeUnit.SECONDS)
		.writeTimeout(20, TimeUnit.SECONDS)
		.retryOnConnectionFailure(true)
		.build()
}

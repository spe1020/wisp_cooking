package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.RecipeSerializer
import cooking.zap.app.relay.HttpClientFactory
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Publishes a recipe as a signed kind-30023 event (concern 2.2) — the shared
 * create spine (Sous Chef "Save" today; the manual recipe-create modal later).
 *
 * Mirrors the web create flow: serialize via [RecipeSerializer], **re-host the
 * cover image** through Blossom so the recipe owns its image (with a fallback
 * to the source URL if re-host fails — Save never blocks on it), sign with the
 * local key, and broadcast to the author's write relays **and**
 * [RelayConfig.ARTICLES_RELAYS] (the "all" publish the web does, so the recipe
 * shows up in the Recipes feed). The just-signed event is cached locally so
 * the detail screen can render it **optimistically** without waiting on relay
 * propagation. Requires a signing key — READ_ONLY can't publish.
 */
class RecipePublisher(
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
    private val blossomRepo: BlossomRepository,
    private val httpClient: OkHttpClient = HttpClientFactory.getGeneralClient(),
) {
    sealed interface Result {
        /** [author]/[dTag] address the just-published recipe (cached locally). */
        data class Published(val author: String, val dTag: String) : Result
        data class Error(val message: String) : Result
    }

    suspend fun publish(
        recipe: RecipeParser.Recipe,
        categories: List<String>,
        signer: NostrSigner?,
        includeClientTag: Boolean,
    ): Result = withContext(Dispatchers.IO) {
        if (signer == null) return@withContext Result.Error("Sign in to save recipes.")
        val title = recipe.title?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.Error("This recipe needs a title to publish.")
        // Image required, mirroring the web (it blocks publish with no image).
        val sourceImage = recipe.image?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.Error("Add an image to publish this recipe.")

        val imageUrl = reHost(sourceImage, signer) ?: sourceImage
        val content = RecipeSerializer.toContent(recipe)
        val tags = RecipeSerializer.toTags(title, recipe.summary, listOf(imageUrl), categories)
            .toMutableList()
        if (includeClientTag) tags.add(listOf("client", "Zap Cooking"))

        val event = signer.signEvent(RecipeParser.RECIPE_KIND, content, tags)
        // Cache first so the detail screen renders optimistically (no relay round-trip).
        eventRepo.cacheEvent(event)

        val msg = ClientMessage.event(event)
        relayPool.sendToWriteRelays(msg)
        // Also broadcast to the article relays the Recipes feed reads.
        for (url in RelayConfig.ARTICLES_RELAYS) relayPool.sendToRelayOrEphemeral(url, msg)

        Result.Published(author = signer.pubkeyHex, dTag = RecipeSerializer.slug(title))
    }

    /** Fetch the remote image and re-upload to Blossom; null on any failure (→ caller falls back). */
    private suspend fun reHost(url: String, signer: NostrSigner): String? = try {
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) return null
            val bytes = body.bytes()
            if (bytes.isEmpty()) return null
            val mime = body.contentType()?.toString()?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val ext = mime.substringAfterLast('/', "jpg").ifBlank { "jpg" }
            blossomRepo.uploadMedia(bytes, mime, ext, signer)
        }
    } catch (e: CancellationException) {
        throw e // never swallow cancellation
    } catch (e: Exception) {
        null // fall back to the source URL
    }
}

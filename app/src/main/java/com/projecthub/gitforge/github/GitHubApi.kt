package com.projecthub.gitforge.github

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.ErrorKind
import com.projecthub.gitforge.core.Redaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A classified GitHub API failure. Never carries a token in its message. */
class GitHubException(
    val kind: ErrorKind,
    override val message: String,
    val httpStatus: Int? = null,
    val rateLimitResetEpochSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thin, dependency-light GitHub REST client.
 *
 * Why hand-rolled rather than a generated SDK: the app needs only a small, stable
 * subset of the REST API (repos, branches, contents, device-flow auth). Keeping
 * the surface explicit makes it easy to guarantee that the `Authorization`
 * header is never logged and that rate-limit headers are always honoured.
 *
 * The token is supplied per call by a provider lambda, so the client itself holds
 * no credential state and can safely outlive a sign-out.
 */
class GitHubApi(
    private val tokenProvider: () -> String?,
    private val baseUrl: String = "https://api.github.com",
    client: OkHttpClient? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------------- account

    suspend fun currentUser(): GitHubUser = get("/user", GitHubUser.serializer())

    /** The scopes GitHub reports for the presented token, from the `X-OAuth-Scopes` header. */
    suspend fun tokenScopes(): String? = withContext(Dispatchers.IO) {
        val response = execute(buildRequest("/user", emptyMap()))
        response.use { it.header("X-OAuth-Scopes") }
    }

    // -------------------------------------------------------------- repositories

    suspend fun repositories(
        page: Int = 1,
        perPage: Int = 30,
        visibility: String = "all",
        affiliation: String = "owner,collaborator,organization_member",
        sort: String = "updated",
    ): Paged<GitHubRepository> {
        val query: Map<String, String> = mapOf(
            "page" to page.toString(),
            "per_page" to perPage.coerceIn(1, 100).toString(),
            "visibility" to visibility,
            "affiliation" to affiliation,
            "sort" to sort,
        )
        val (items, links) = getWithLinks("/user/repos", ListSerializer(GitHubRepository.serializer()), query)
        return items.toPage(page, links)
    }

    suspend fun repository(owner: String, name: String): GitHubRepository =
        get("/repos/$owner/$name", GitHubRepository.serializer())

    suspend fun searchRepositories(query: String, page: Int = 1, perPage: Int = 30): Paged<GitHubRepository> {
        val encoded = query.trim()
        if (encoded.isEmpty()) throw GitHubException(ErrorKind.VALIDATION, "Search query is empty")
        val (wrapper, links) = getWithLinks(
            "/search/repositories",
            SearchWrapper.serializer(),
            mapOf("q" to encoded, "page" to page.toString(), "per_page" to perPage.coerceIn(1, 100).toString()),
        )
        return wrapper.items.toPage(page, links)
    }

    // ------------------------------------------------------------------- branches

    suspend fun branches(owner: String, name: String, page: Int = 1, perPage: Int = 100): Paged<GitHubBranch> {
        val (items, links) = getWithLinks(
            "/repos/$owner/$name/branches",
            ListSerializer(GitHubBranch.serializer()),
            mapOf("page" to page.toString(), "per_page" to perPage.coerceIn(1, 100).toString()),
        )
        return items.toPage(page, links)
    }

    // -------------------------------------------------------------------- contents

    suspend fun contents(owner: String, name: String, path: String = "", ref: String? = null): List<GitHubContent> {
        val encodedPath = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
        val suffix = if (encodedPath.isEmpty()) "" else "/$encodedPath"
        val query: Map<String, String> = ref?.let { mapOf("ref" to it) } ?: emptyMap()
        // A directory decodes as an array; a single file decodes as one object.
        val raw: kotlinx.serialization.json.JsonElement =
            get("/repos/$owner/$name/contents$suffix", kotlinx.serialization.json.JsonElement.serializer(), query)
        return if (raw is kotlinx.serialization.json.JsonArray) {
            json.decodeFromJsonElement(ListSerializer(GitHubContent.serializer()), raw)
        } else {
            listOf(json.decodeFromJsonElement(GitHubContent.serializer(), raw))
        }
    }

    /** Creates or updates a file directly through the API (used for small edits). */
    suspend fun putFile(
        owner: String,
        name: String,
        path: String,
        contentBase64: String,
        message: String,
        branch: String,
        sha: String? = null,
    ): GitHubContent {
        val body = buildString {
            append("{\"message\":").append(jsonEncode(message))
            append(",\"content\":").append(jsonEncode(contentBase64))
            append(",\"branch\":").append(jsonEncode(branch))
            if (sha != null) append(",\"sha\":").append(jsonEncode(sha))
            append("}")
        }
        return requestWithBody(
            method = "PUT",
            path = "/repos/$owner/$name/contents/${path.split('/').joinToString("/") { encodeSegment(it) }}",
            bodyJson = body,
            deserializer = GitHubContent.serializer(),
        )
    }

    // ------------------------------------------------------------------ rate limit

    suspend fun rateLimit(): GitHubRateLimit {
        val wrapper: RateLimitWrapper = get("/rate_limit", RateLimitWrapper.serializer())
        return wrapper.resources.core
    }

    // ------------------------------------------------------------------ internals

    private suspend fun <T> get(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        query: Map<String, String> = emptyMap(),
    ): T = getWithLinks(path, deserializer, query).first

    /**
     * Performs a GET and decodes the body. The deserializer is passed explicitly
     * rather than relying on a reified type parameter, because the function both
     * suspends and takes a body, which rules out `inline`.
     */
    private suspend fun <T> getWithLinks(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        query: Map<String, String> = emptyMap(),
    ): Pair<T, PageLinks> = withContext(Dispatchers.IO) {
        val request = buildRequest(path, query)
        execute(request).use { response ->
            val bodyText = response.body?.string().orEmpty()
            val links = parseLinkHeader(response.header("Link"))
            val decoded = json.decodeFromString(deserializer, bodyText)
            decoded to links
        }
    }

    private suspend fun <T> requestWithBody(
        method: String,
        path: String,
        bodyJson: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = (baseUrl + path).toHttpUrlOrNull()
            ?: throw GitHubException(ErrorKind.VALIDATION, "Malformed GitHub URL")
        val builder = Request.Builder()
            .url(url)
            .method(method, bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
        tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }

        execute(builder.build()).use { response ->
            json.decodeFromString(deserializer, response.body?.string().orEmpty())
        }
    }

    private fun buildRequest(path: String, query: Map<String, String>): Request {
        val urlBuilder = (baseUrl + path).toHttpUrlOrNull()
            ?.newBuilder()
            ?: throw GitHubException(ErrorKind.VALIDATION, "Malformed GitHub URL")
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }

        val builder = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)

        // The header is attached here and never logged or echoed.
        tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    /**
     * Performs the call and translates every non-success status into a
     * [GitHubException] whose message is safe to show and to log.
     */
    private fun execute(request: Request): Response {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GitHubException(
                ErrorKind.NETWORK,
                "Could not reach GitHub. Check the connection and try again.",
                cause = e,
            )
        }

        if (response.isSuccessful) return response

        val status = response.code
        val remaining = response.header("X-RateLimit-Remaining")
        val resetHeader = response.header("X-RateLimit-Reset")?.toLongOrNull()
        val retryAfterSecs = response.header("Retry-After")?.toLongOrNull()
        val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
        response.close()

        val detail = extractMessage(errorBody)
        // Honor Retry-After when the reset header is absent (429s often omit it).
        val reset = resetHeader ?: retryAfterSecs?.let { (System.currentTimeMillis() / 1000) + it }
        val rateLimitMessage = (detail.orEmpty() + " " + errorBody).let {
            it.contains("rate limit", ignoreCase = true) ||
                it.contains("abuse", ignoreCase = true) ||
                it.contains("secondary rate", ignoreCase = true)
        }
        val kind = when {
            status == 401 -> ErrorKind.AUTH
            status == 429 -> ErrorKind.RATE_LIMIT
            status == 403 && (remaining == "0" || retryAfterSecs != null || rateLimitMessage) -> ErrorKind.RATE_LIMIT
            status == 403 -> ErrorKind.AUTH
            status == 404 -> ErrorKind.NOT_FOUND
            status == 409 -> ErrorKind.CONFLICT
            status == 422 -> ErrorKind.VALIDATION
            status >= 500 -> ErrorKind.NETWORK
            else -> ErrorKind.UNKNOWN
        }
        val message = when (kind) {
            ErrorKind.AUTH -> "GitHub rejected the credential (HTTP $status). Reconnect the account. ${detail.orEmpty()}"
            ErrorKind.RATE_LIMIT -> "GitHub rate limit reached. Try again after the reset window."
            ErrorKind.NOT_FOUND -> "Not found on GitHub (HTTP 404). ${detail.orEmpty()}"
            ErrorKind.NETWORK -> "GitHub is unavailable right now (HTTP $status)."
            else -> "GitHub request failed (HTTP $status). ${detail.orEmpty()}"
        }
        AppLog.w("GitHub API error $status: ${Redaction.redact(detail)}")
        throw GitHubException(kind, message.trim(), status, reset)
    }

    private fun extractMessage(body: String): String? = runCatching {
        json.decodeFromString<GitHubErrorBody>(body).message
    }.getOrNull()

    private fun parseLinkHeader(header: String?): PageLinks {
        if (header.isNullOrBlank()) return PageLinks(null, null, null, null)
        var next: Int? = null
        var last: Int? = null
        var first: Int? = null
        var prev: Int? = null
        header.split(',').forEach { part ->
            val rel = Regex("""rel="([^"]+)"""").find(part)?.groupValues?.get(1) ?: return@forEach
            val page = Regex("""[?&]page=(\d+)""").find(part)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            when (rel) {
                "next" -> next = page
                "last" -> last = page
                "first" -> first = page
                "prev" -> prev = page
            }
        }
        return PageLinks(next, last, first, prev)
    }

    private fun <T> List<T>.toPage(page: Int, links: PageLinks): Paged<T> = Paged(
        items = this,
        page = page,
        nextPage = links.next,
        lastPage = links.last,
        hasMore = links.next != null,
    )

    private fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun jsonEncode(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()

    private companion object {
        const val API_VERSION = "2022-11-28"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@kotlinx.serialization.Serializable
internal data class SearchWrapper(
    @kotlinx.serialization.SerialName("total_count") val totalCount: Int = 0,
    val items: List<GitHubRepository> = emptyList(),
)

@kotlinx.serialization.Serializable
internal data class RateLimitWrapper(val resources: RateLimitResources)

@kotlinx.serialization.Serializable
internal data class RateLimitResources(val core: GitHubRateLimit)

@kotlinx.serialization.Serializable
internal data class GitHubErrorBody(val message: String? = null)

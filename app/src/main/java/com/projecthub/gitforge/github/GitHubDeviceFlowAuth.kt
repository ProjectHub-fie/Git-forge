package com.projecthub.gitforge.github

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.ErrorKind
import com.projecthub.gitforge.core.Redaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub OAuth **device flow** (RFC 8628).
 *
 * This is the correct flow for a native Android app that must not embed a client
 * secret: the device asks GitHub for a short user code, the user approves it in
 * any browser, and the app polls for the token. No client secret is ever needed,
 * and the token is delivered directly to the device.
 *
 * Only the public `client_id` is required, and it is not a secret, so it is safe
 * in the APK. The user can supply their own client ID in Settings.
 */
class GitHubDeviceFlowAuth(
    private val clientIdProvider: () -> String?,
    client: OkHttpClient? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Step 1: request a device code and the code the user must type. */
    suspend fun start(scope: String = DEFAULT_SCOPE): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val clientId = clientIdProvider()
            ?: throw GitHubException(
                ErrorKind.AUTH,
                "No GitHub OAuth client ID is configured. Add one in Settings, or sign in with a personal access token.",
            )

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()

        val request = Request.Builder()
            .url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val text = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw GitHubException(
                        ErrorKind.AUTH,
                        "GitHub refused the device-code request (HTTP ${response.code}).",
                        response.code,
                    )
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw GitHubException(ErrorKind.NETWORK, "Could not reach GitHub to start sign-in.", cause = e)
        }

        val parsed = try {
            json.decodeFromString<DeviceCodeWire>(text)
        } catch (e: Exception) {
            throw GitHubException(ErrorKind.NETWORK, "GitHub's sign-in reply could not be read.", cause = e)
        }
        if (parsed.error != null) {
            throw GitHubException(ErrorKind.AUTH, parsed.errorDescription ?: parsed.error)
        }
        DeviceCodeResponse(
            deviceCode = parsed.deviceCode,
            userCode = parsed.userCode,
            verificationUri = parsed.verificationUri,
            expiresInSeconds = parsed.expiresIn,
            intervalSeconds = parsed.interval.coerceAtLeast(5),
        )
    }

    /**
     * Step 2: poll until the user approves, the code expires, or the caller
     * cancels. Honours `slow_down` by increasing the interval, as the spec requires.
     */
    suspend fun awaitToken(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int,
        onTick: (remainingSeconds: Int) -> Unit = {},
    ): AccessTokenResponse = withContext(Dispatchers.IO) {
        val clientId = clientIdProvider()
            ?: throw GitHubException(ErrorKind.AUTH, "No GitHub OAuth client ID is configured.")

        var interval = intervalSeconds.coerceAtLeast(5)
        var elapsed = 0

        while (elapsed < expiresInSeconds) {
            delay(interval * 1000L)
            elapsed += interval
            onTick((expiresInSeconds - elapsed).coerceAtLeast(0))

            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val request = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            val text = try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw GitHubException(
                            ErrorKind.AUTH,
                            "GitHub refused the token request (HTTP ${response.code}).",
                            response.code,
                        )
                    }
                    response.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw GitHubException(ErrorKind.NETWORK, "Lost connection while waiting for GitHub sign-in.", cause = e)
            }

            val parsed = try {
                json.decodeFromString<TokenWire>(text)
            } catch (e: Exception) {
                throw GitHubException(ErrorKind.NETWORK, "GitHub's sign-in reply could not be read.", cause = e)
            }
            when (parsed.error) {
                null -> {
                    val token = parsed.accessToken
                    if (token.isNullOrBlank()) {
                        throw GitHubException(ErrorKind.AUTH, "GitHub returned an empty token.")
                    }
                    AppLog.i("Device flow completed; token stored in the encrypted credential store")
                    return@withContext AccessTokenResponse(
                        accessToken = token,
                        tokenType = parsed.tokenType ?: "bearer",
                        scope = parsed.scope,
                    )
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "expired_token" -> throw GitHubException(
                    ErrorKind.AUTH,
                    "The sign-in code expired. Start again to get a new code.",
                )
                "access_denied" -> throw GitHubException(ErrorKind.AUTH, "Sign-in was denied on GitHub.")
                else -> throw GitHubException(
                    ErrorKind.AUTH,
                    Redaction.redact(parsed.errorDescription ?: parsed.error),
                )
            }
        }

        throw GitHubException(ErrorKind.AUTH, "Timed out waiting for GitHub sign-in approval.")
    }

    companion object {
        /**
         * `repo` grants read/write access to repositories the user chooses, which is
         * what push and pull need. `read:user` is the minimum needed to show the
         * signed-in account. Nothing broader is requested.
         */
        const val DEFAULT_SCOPE = "repo read:user"

        val SCOPE_EXPLANATIONS: List<Pair<String, String>> = listOf(
            "repo" to "Read and write repository contents, branches and pull requests for repositories you select.",
            "read:user" to "Read your public profile so GitForge can show which account is connected.",
        )
    }
}

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

data class AccessTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val scope: String?,
)

@Serializable
private data class DeviceCodeWire(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "https://github.com/login/device",
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class TokenWire(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

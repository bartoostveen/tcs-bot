package nl.bartoostveen.tcsbot.routing

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.crackthecodeabhi.kreds.args.SetOption
import io.github.crackthecodeabhi.kreds.connection.KredsClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.util.generateNonceSuspend
import kotlinx.html.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.dv8tion.jda.api.JDA
import nl.bartoostveen.tcsbot.AppConfig
import nl.bartoostveen.tcsbot.command.assignRole
import nl.bartoostveen.tcsbot.database.getMemberByNonce
import nl.bartoostveen.tcsbot.util.*
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.hours

private const val SCOPE = "openid profile email"
private val REDIRECT_URI = "${AppConfig.HOSTNAME}/oauth/callback"

private val jwksProvider =
  JwkProviderBuilder(
    URI(
      "${AppConfig.MICROSOFT_AUTH_ENDPOINT}/discovery/keys?appid=${AppConfig.MICROSOFT_CLIENT_ID}"
    ).toURL()
  )
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.HOURS)
    .build()

private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

fun Route.authRouter(
  jda: JDA,
  redis: KredsClient
) = route("/oauth") {
  get("/redirect") {
    val nonce = queryParameter("nonce")
    if (getMemberByNonce(nonce, fetchGuilds = false) == null) badRequest("Invalid nonce")

    val codeVerifier = generateNonceSuspend(43)
    val codeChallenge = base64.encode(codeVerifier.toByteArray().sha256)
    runCatching {
      redis.set(
        key = "nonce:$nonce",
        value = codeVerifier,
        setOption = SetOption.Builder().exSeconds(1.hours.inWholeSeconds.toULong()).build()
      )
    }.printException().onFailure { internalServerError() }

    call.respondRedirect(
      URLBuilder("${AppConfig.MICROSOFT_AUTH_ENDPOINT}/oauth2/v2.0/authorize")
        .apply {
          parameters["client_id"] = AppConfig.MICROSOFT_CLIENT_ID
          parameters["response_type"] = "code"
          parameters["redirect_uri"] = REDIRECT_URI
          parameters["scope"] = SCOPE
          parameters["state"] = nonce
          parameters["nonce"] = nonce
          parameters["response_mode"] = "query"
          parameters["code_challenge"] = codeChallenge
          parameters["code_challenge_method"] = "S256"
        }
        .build()
    )
  }

  get("/callback") {
    call.parameters["error_description"]?.let {
      badRequest(it)
    }

    val code = queryParameter("code")
    val nonce = queryParameter("state")
    val codeVerifier = runCatching {
      redis.getDel("nonce:$nonce")
    }.getOrNull() ?: badRequest("Invalid state")

    val jwt = runCatching {
      AppConfig.httpClient.submitForm(
        url = "${AppConfig.MICROSOFT_AUTH_ENDPOINT}/oauth2/v2.0/token",
        formParameters = Parameters.build {
          append("client_id", AppConfig.MICROSOFT_CLIENT_ID)
          append("client_secret", AppConfig.MICROSOFT_CLIENT_SECRET)
          append("scope", SCOPE)
          append("redirect_uri", REDIRECT_URI)
          append("grant_type", "authorization_code")
          append("code", code)
          append("code_verifier", codeVerifier)
        }
      ).body<OpenIDConnectTokenResponse>()
    }
      .mapCatching { JWT.decode(it.idToken) }
      .printException()
      .getOrElse { badRequest("Could not get token") }

    runCatching {
      if (jwt.getClaim("nonce").asNullableString != nonce) badRequest("Invalid token")
      val jwk = jwksProvider.get(jwt.getHeaderClaim("kid").asNullableString)
      val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
      algorithm.verify(jwt)
    }.printException().onFailure { badRequest("Invalid token: signature seems to be invalid") }

    runCatching {
      val name = let {
        val firstName = jwt.string("given_name") ?: return@let null
        val familyName = jwt.string("family_name") ?: return@let null
        "$firstName $familyName"
      } ?: jwt.string("name") ?: badRequest("Invalid token: does not contain name")
      val email = jwt.string("email") ?: badRequest("Invalid token: does not contain email")

      val success = runCatching {
        jda.assignRole(name, email, nonce)
      }.printException().getOrDefault(false)
      if (!success) throw RuntimeException("JDA failed to assign role")
      call.respondHtml {
        head {
          title { +"Success!" }
        }
        body {
          h1 { +"If you can read this, you've successfully linked your Discord account." }
          p { +"You can now close this page" }
          p {
            +"You are currently authenticated as: $name <$email>."
            +"If you want to link your UT account to a different Discord account, perform /unlink in order to do so."
          }
        }
      }
    }.printException().onFailure {
      internalServerError("Cannot assign role, try again later")
    }
  }
}

@Serializable
data class OpenIDConnectTokenResponse(
  @SerialName("access_token")
  val accessToken: String,
  @SerialName("id_token")
  val idToken: String,
  @SerialName("expires_in")
  val expiresIn: Long,
  val scope: String,
)

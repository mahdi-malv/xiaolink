package wtf.mahdi.glassesconnect.cloud

import android.content.SharedPreferences
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Fetches the glasses' encrypt_key (WearAuth token) from Xiaomi cloud. Port of tools/fetch_key.py.
 * Glasses must already be bound to the account (official CN app). All calls block: run them off the main thread.
 *
 * APK evidence:
 *   sid "health-glasses"                     library/base/basecommon/config/d.java:96
 *   host https://hlth.io.mi.com (CN)         library/base/basecommon/config/c.java:74
 *   POST /glasses/source/get_source_list     MiWearBindService.java:67
 *   RC4 signing                              com/xiaomi/common/crypt/CloudUtil.java
 *   cookies                                  AddMiWearCookiesInterceptor.java:45-91
 */
class XiaomiCloud(private val prefs: SharedPreferences) {
    data class Auth(val userId: String, val cUserId: String, val ssecurity: String, val serviceToken: String)
    data class Device(val model: String, val name: String, val did: String, val mac: String, val encryptKey: String)

    sealed interface LoginResult {
        data class Done(val auth: Auth) : LoginResult
        /** 2FA required. [email]/[sms] tell which code channels the account supports. */
        data class NeedsCode(val email: Boolean, val sms: Boolean) : LoginResult
    }

    class CloudException(message: String) : Exception(message)

    private val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        ?: (1..16).map { "ABCDEF0123456789"[random.nextInt(16)] }.joinToString("").also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    private val jar = Jar()
    private val client = OkHttpClient.Builder()
        .cookieJar(jar)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Android-7.1.1-1.0.0-ONEPLUS A3010-136-$deviceId APP/xiaomi.smarthome APPV/62830").build())
        }
        .build()

    /** Pending 2FA context between [login] and [sendCode]/[verifyCode]. */
    private var twoFactorContext: String? = null

    /** Reuses the saved passToken session when possible; otherwise needs [user] and [password]. */
    fun login(user: String, password: String): LoginResult {
        val r = serviceLogin()
        if (r.has("ssecurity") && r.has("location")) return LoginResult.Done(finish(r))
        if (user.isBlank() || password.isEmpty()) throw CloudException("Saved session expired: enter account and password")
        val form = FormBody.Builder()
            .add("sid", SID).add("hash", md5(password.toByteArray()).hex().uppercase())
            .add("callback", r.optString("callback")).add("qs", r.optString("qs")).add("_sign", r.optString("_sign"))
            .add("user", user).add("_json", "true").build()
        val r2 = parse(post("$ACCOUNT/pass/serviceLoginAuth2".toHttpUrl(), form))
        r2.optString("notificationUrl").takeIf { it.isNotEmpty() }?.let { return startTwoFactor(it) }
        if (!r2.has("ssecurity")) throw CloudException("Login failed: ${r2.optString("desc", r2.toString())}")
        return LoginResult.Done(finish(r2))
    }

    /** Sends the 2FA code by e-mail or SMS. */
    fun sendCode(email: Boolean) {
        val context = twoFactorContext ?: throw CloudException("No pending verification")
        val kind = if (email) "Email" else "Phone"
        val url = identityUrl("/identity/auth/send${kind}Ticket", context)
        val form = FormBody.Builder().add("retry", "0").add("icode", "").add("_json", "true").add("ick", jar.value("identity_session")).build()
        val sent = parse(post(url, form))
        if (sent.has("code") && sent.optInt("code") != 0) throw CloudException("Sending code failed: $sent")
    }

    /** Verifies the 2FA code, then completes login. */
    fun verifyCode(email: Boolean, code: String): Auth {
        val context = twoFactorContext ?: throw CloudException("No pending verification")
        val kind = if (email) "Email" else "Phone"
        val flag = if (email) "8" else "4"
        val url = identityUrl("/identity/auth/verify$kind", context).newBuilder()
            .addQueryParameter("_flag", flag).addQueryParameter("_json", "true").build()
        val form = FormBody.Builder().add("_flag", flag).add("ticket", code.trim()).add("trust", "true")
            .add("_json", "true").add("ick", jar.value("identity_session")).build()
        val verified = parse(post(url, form))
        val location = verified.optString("location")
        if (verified.optInt("code", -1) != 0 || location.isEmpty()) throw CloudException("Verification failed: $verified")
        get(location.toHttpUrl()).close() // sets passToken / userId cookies
        twoFactorContext = null
        val r = serviceLogin()
        if (!r.has("ssecurity")) throw CloudException("Login failed after verification: ${r.optString("desc", r.toString())}")
        return finish(r)
    }

    /** Lists glasses bound to the account, with their encrypt_key. */
    fun devices(auth: Auth): List<Device> {
        val nonceRaw = ByteArray(8).also(random::nextBytes) +
            (System.currentTimeMillis() / 60000).toInt().let { byteArrayOf((it shr 24).toByte(), (it shr 16).toByte(), (it shr 8).toByte(), it.toByte()) }
        val signedNonce = sha256(b64d(auth.ssecurity) + nonceRaw)
        val params = sortedMapOf("data" to """{"model":"","page_size":100,"status":1}""")
        params["rc4_hash__"] = b64(sha1Sig("POST", PATH, params, b64(signedNonce)))
        val rc4 = Rc4(signedNonce)
        val enc = params.mapValuesTo(sortedMapOf()) { (_, v) -> b64(rc4.crypt(v.toByteArray())) }
        enc["signature"] = b64(sha1Sig("POST", PATH, enc, b64(signedNonce)))
        enc["_nonce"] = b64(nonceRaw)
        val form = FormBody.Builder().apply { enc.forEach { (k, v) -> add(k, v) } }.build()
        val cookies = "serviceToken=${auth.serviceToken}; userId=${auth.userId}; cUserId=${auth.cUserId}; auth_key=$AUTH_KEY; locale=zh_CN"
        val request = Request.Builder().url(HOST + PATH).post(form).header("Cookie", cookies).build()
        // Cookies are set by hand here, like the official app; keep the account jar out of it.
        val (status, body) = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build().newCall(request).execute()
            .use { it.code to it.body?.string().orEmpty() }
        val res = runCatching { JSONObject(String(Rc4(signedNonce).crypt(b64d(body)))) }
            .getOrElse { throw CloudException("HTTP $status, cannot decrypt response: ${body.take(300)}") }
        val list = (res.optJSONObject("result") ?: res.optJSONObject("data"))?.optJSONArray("list")
            ?: throw CloudException("No devices. Raw response: ${res.toString().take(800)}")
        return (0 until list.length()).map { i ->
            val d = list.getJSONObject(i)
            val detail = d.optJSONObject("detail") ?: JSONObject()
            Device(d.optString("model"), d.optString("name"), d.optString("sid"), detail.optString("mac"), detail.optString("encrypt_key"))
        }
    }

    /** Forgets the saved passToken session. */
    fun logout() { jar.clear(); twoFactorContext = null }

    private fun serviceLogin(): JSONObject =
        parse(get("$ACCOUNT/pass/serviceLogin".toHttpUrl().newBuilder().addQueryParameter("sid", SID).addQueryParameter("_json", "true").build()))

    private fun startTwoFactor(notificationUrl: String): LoginResult {
        val context = notificationUrl.toHttpUrl().queryParameter("context").orEmpty()
        get(notificationUrl.toHttpUrl()).close() // sets identity_session cookie
        twoFactorContext = context
        val options = parse(get("$ACCOUNT/identity/list".toHttpUrl().newBuilder()
            .addQueryParameter("sid", SID).addQueryParameter("supportedMask", "0").addQueryParameter("context", context).build()))
        val methods = options.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
            ?: listOfNotNull(options.optInt("flag", 0).takeIf { it != 0 })
        return LoginResult.NeedsCode(email = 8 in methods, sms = 4 in methods || 8 !in methods)
    }

    private fun finish(r: JSONObject): Auth {
        get(r.getString("location").toHttpUrl()).close()
        val token = jar.value("serviceToken").ifEmpty { throw CloudException("No serviceToken in login redirect") }
        jar.save()
        return Auth(r.get("userId").toString(), r.optString("cUserId"), r.getString("ssecurity"), token)
    }

    private fun identityUrl(path: String, context: String): HttpUrl = "$ACCOUNT$path".toHttpUrl().newBuilder()
        .addQueryParameter("_dc", System.currentTimeMillis().toString()).addQueryParameter("sid", SID)
        .addQueryParameter("context", context).addQueryParameter("mask", "0").addQueryParameter("_locale", "en_US").build()

    private fun get(url: HttpUrl): Response = client.newCall(Request.Builder().url(url).build()).execute()
    private fun post(url: HttpUrl, form: FormBody): Response = client.newCall(Request.Builder().url(url).post(form).build()).execute()

    private fun parse(response: Response): JSONObject = response.use {
        val text = it.body?.string().orEmpty().replace("&&&START&&&", "")
        runCatching { JSONObject(text) }.getOrElse { _ ->
            throw CloudException("Unexpected response (HTTP ${it.code}) from ${it.request.url}: ${text.take(500)}")
        }
    }

    /** Cookie jar persisted to prefs. Like the script, only xiaomi.com cookies survive, as domain cookies. */
    private inner class Jar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        init {
            val saved = runCatching { JSONObject(prefs.getString(KEY_COOKIES, "{}")!!) }.getOrDefault(JSONObject())
            saved.keys().forEach { name -> cookies += cookie(name, saved.getString(name)) }
            cookies += cookie("sdkVersion", "accountsdk-18.8.15")
            cookies += cookie("deviceId", deviceId)
        }

        private fun cookie(name: String, value: String) = Cookie.Builder().name(name).value(value).domain("xiaomi.com").path("/").build()

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
            this.cookies += cookies
        }

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }

        @Synchronized fun value(name: String): String = cookies.lastOrNull { it.name == name }?.value.orEmpty()

        @Synchronized fun save() {
            val json = JSONObject()
            cookies.filter { it.domain.endsWith("xiaomi.com") }.forEach { json.put(it.name, it.value) }
            prefs.edit().putString(KEY_COOKIES, json.toString()).apply()
        }

        @Synchronized fun clear() {
            cookies.removeAll { it.name != "sdkVersion" && it.name != "deviceId" }
            prefs.edit().remove(KEY_COOKIES).apply()
        }
    }

    /** RC4 with the first 1024 keystream bytes dropped (CloudUtil). */
    class Rc4(key: ByteArray) {
        private val s = IntArray(256) { it }
        private var i = 0
        private var j = 0

        init {
            var k = 0
            for (n in 0 until 256) {
                k = (k + s[n] + (key[n % key.size].toInt() and 0xFF)) and 0xFF
                s[n] = s[k].also { s[k] = s[n] }
            }
            crypt(ByteArray(1024))
        }

        fun crypt(data: ByteArray): ByteArray = ByteArray(data.size) { n ->
            i = (i + 1) and 0xFF; j = (j + s[i]) and 0xFF
            s[i] = s[j].also { s[j] = s[i] }
            (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
    }

    companion object {
        const val SID = "health-glasses"
        const val HOST = "https://hlth.io.mi.com"
        const val PATH = "/glasses/source/get_source_list"
        const val AUTH_KEY = "rwelJuWBFJxmbMKD" // hard-coded in AddMiWearCookiesInterceptor
        private const val ACCOUNT = "https://account.xiaomi.com"
        private const val KEY_COOKIES = "cloud_cookies"
        private const val KEY_DEVICE_ID = "cloud_device_id"
        private val random = SecureRandom()

        /** SHA1 over "METHOD&path&k=v...&signedNonce" with params sorted by key. */
        fun sha1Sig(method: String, path: String, params: Map<String, String>, signedNonce: String): ByteArray {
            val parts = listOf(method.uppercase(), path) + params.toSortedMap().map { (k, v) -> "$k=$v" } + signedNonce
            return MessageDigest.getInstance("SHA-1").digest(parts.joinToString("&").toByteArray())
        }

        private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)
        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun b64d(s: String) = Base64.decode(s, Base64.DEFAULT)
    }
}

package com.hotomeasure.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer release on GitHub. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 that GitHub publishes for the asset, when it does. */
    val sha256: String?,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Handed to Android; it shows its own confirmation. */
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates the app from this project's GitHub releases. The only network access in the app:
 * api.github.com for the latest release, and the release's own download link.
 *
 * A download is installed only if its SHA-256 matches the one GitHub lists for it, it is this
 * same app with a higher version, and it is signed with the same key as the installed app.
 */
object Updater {
    const val REPO = "computir/hoto-laser-measure"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ASSET = "LaserMeasure.apk"
    private const val MAX_APK_BYTES = 50L * 1024 * 1024
    private val ALLOWED_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    internal fun set(s: UpdateState) { _state.value = s }

    fun installedVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /**
     * True when the phone forbids apps from installing APKs at all, e.g. Android's Advanced
     * Protection or a work profile policy. Then only a PC (adb) or an app store can update us.
     */
    fun installsBlocked(ctx: Context): Boolean {
        val um = ctx.getSystemService(android.os.UserManager::class.java) ?: return false
        return um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY) ||
            um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
    }

    /** Returns the newer release, or null when this is already the latest. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val json = Json.parseToJsonElement(get(API).decodeToString()).jsonObject
            val release = parse(json)
            val current = installedVersion(ctx)
            if (release != null && isNewer(release.version, current)) {
                _state.value = UpdateState.Available(release)
                release
            } else {
                _state.value = UpdateState.UpToDate(current)
                null
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't check for updates: ${e.message ?: "no connection"}")
            null
        }
    }

    /** Downloads, verifies and hands the APK to Android's installer. */
    suspend fun downloadAndInstall(ctx: Context, release: Release) = withContext(Dispatchers.IO) {
        try {
            val file = download(ctx, release)
            verify(ctx, file, release)
            _state.value = UpdateState.Installing
            install(ctx, file)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Update failed")
        }
    }

    // --- GitHub ---

    internal fun parse(json: JsonObject): Release? {
        val tag = json["tag_name"]?.jsonPrimitive?.content ?: return null
        val asset = json["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == ASSET } ?: return null
        val digest = asset["digest"]?.jsonPrimitive?.content
        return Release(
            version = tag.removePrefix("v"),
            notes = json["body"]?.jsonPrimitive?.content.orEmpty(),
            apkUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: return null,
            apkSize = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1,
            sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            pageUrl = json["html_url"]?.jsonPrimitive?.content ?: "https://github.com/$REPO/releases",
        )
    }

    /** Compares dotted versions numerically: 1.10 is newer than 1.9. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection {
        var target = URL(url)
        // Follow redirects ourselves so every hop stays on HTTPS and on GitHub.
        repeat(5) {
            if (target.protocol != "https" || target.host !in ALLOWED_HOSTS) throw IOException("Unexpected download location ${target.host}")
            val c = target.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            c.setRequestProperty("User-Agent", "LaserMeasure-Android")
            when (c.responseCode) {
                in 200..299 -> return c
                301, 302, 303, 307, 308 -> {
                    target = URL(target, c.getHeaderField("Location") ?: throw IOException("Bad redirect"))
                    c.disconnect()
                }
                else -> throw IOException("GitHub returned ${c.responseCode}")
            }
        }
        throw IOException("Too many redirects")
    }

    private fun get(url: String): ByteArray = open(url).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }

    private fun download(ctx: Context, release: Release): File {
        if (release.apkSize > MAX_APK_BYTES) throw IOException("Update file is unexpectedly large")
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, ASSET)
        val c = open(release.apkUrl)
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("Update file is unexpectedly large")
                        out.write(buf, 0, n)
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return file
    }

    // --- Verification ---

    private fun verify(ctx: Context, file: File, release: Release) {
        release.sha256?.let { expected ->
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != expected) throw SecurityException("The download is damaged or was altered (checksum mismatch). Not installed.")
        }
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw SecurityException("The download isn't a valid app. Not installed.")
        if (archive.packageName != ctx.packageName) throw SecurityException("The download is a different app. Not installed.")
        val installed = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) throw IOException("The download isn't newer than this version.")
        if (signers(archive) != signers(installed) || signers(installed).isEmpty()) {
            throw SecurityException("The download isn't signed by this app's publisher. Not installed.")
        }
    }

    private fun signers(info: android.content.pm.PackageInfo): Set<String> {
        val s = info.signingInfo ?: return emptySet()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        // With key rotation the history holds older keys too; compare the current one.
        val current = if (s.hasMultipleSigners()) certs.toList() else listOfNotNull(certs?.lastOrNull())
        return current.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    // --- Install ---

    private fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            ctx.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putBoolean("relaunch", true).apply()
            val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

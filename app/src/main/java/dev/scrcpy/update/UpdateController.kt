package dev.scrcpy.update

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class UpdateController protected constructor(
    private val context: Context,
    private val repository: String,
    private val asset: String,
    testStorage: String? = null
) {
    companion object {
        private var shared: UpdateController? = null
        @Synchronized fun get(context: Context, repository: String, asset: String): UpdateController =
            shared ?: UpdateController(requireNotNull(context.applicationContext), repository, asset).also { shared = it }
        @Suppress("DEPRECATION")
        private fun code(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        @Suppress("DEPRECATION")
        private fun signers(info: PackageInfo): Set<String> {
            val signatures: Array<out Signature?>? =
                if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().filterNotNull().map { signature ->
                ReleasePolicy.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
            }.toSet()
        }
    }
    fun interface Observer { fun changed() }
    private val prefs: SharedPreferences
    private val endpoint = "https://api.github.com/repos/arisewanggithub/$repository/releases/latest"
    private val main = Handler(Looper.getMainLooper())
    private var observer: Observer? = null
    private var client: ReleaseClient? = null
    private var executor: ExecutorService? = null
    @Volatile private var generation = 0L
    var release: ReleasePolicy.Manifest? = null
    private var apkUrl: String? = null
    var status = "Check for a public stable release."
    var busy = false
    // Explicit update consent survives rotation, never process restart.
    var finishUpdateRequested = false
    var checkSucceeded = false
        protected set
    var verified = false
    var downloaded = 0L
    var total = 0L
    val apk: File
    var installedCode: Long
    var installedName: String
    private var retained = false
    private var showInstallOutcome = true

    init {
        require(testStorage == null || testStorage.matches(Regex("update_test_[a-z_]{1,40}")))
        require(repository in setOf("ScrcpySend", "ScrcpyReceive"))
        prefs = requireNotNull(context.getSharedPreferences(testStorage ?: "public_release_update", Context.MODE_PRIVATE))
        val directory = File(context.cacheDir, testStorage ?: "release_update").apply { mkdirs() }
        apk = File(directory, "verified.apk")
        // An installer return may outlive the process. Retain its file, never its verification verdict.
        directory.listFiles { _, name -> name.matches(Regex("download-[0-9]+\\.part")) }?.forEach { it.delete() }
        try {
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            installedCode = code(installed)
            installedName = installed.versionName ?: "(unknown)"
        } catch (e: Exception) {
            throw IllegalStateException("Installed app metadata unavailable", e)
        }
        try {
            val cached = ReleasePolicy.parse(prefs.getString("manifest", "") ?: "", context.packageName, asset)
            apkUrl = ReleasePolicy.trustedUrl(prefs.getString("apk_url", "") ?: "")
            release = cached
            status = if (cached.versionCode > installedCode) "Update available: ${cached.versionName}"
                else "Installed version is current (cached check)."
        } catch (_: Exception) { release = null }
        retained = apk.isFile && release?.versionCode?.toLong() == installTargetVersion()
        if (!retained) apk.delete()
        installMessage()?.let { status = it }
    }
    fun available(): Boolean = release?.let { it.versionCode > installedCode } ?: false
    open fun browserReleaseUrl(): String {
        check(available()) { "No newer release is available" }
        val url = apkUrl ?: throw IllegalStateException("Release download URL is unavailable")
        return ReleasePolicy.repositoryReleasePage(url, repository, asset)
    }
    fun refreshVersion() {
        if (!refreshInstalledVersion()) {
            status = "Cannot read the installed app version. Restart the app and retry."
            android.util.Log.e("ScrcpyAppUpdate", "operation=REFRESH_VERSION failed=PACKAGE_METADATA")
        } else if (!available()) {
            status = "Installed version is current."
        }
    }
    fun downloadReady(): Boolean = (verified || retained) && apk.isFile && available()
    fun installTargetVersion(): Long = prefs.getLong("install_target_version", 0)
    fun installPending(): Boolean = prefs.getString("install_outcome", "") == "pending"
    fun installNeedsRetry(): Boolean = prefs.getString("install_outcome", "") in setOf("cancelled", "failed", "unconfirmed")
    fun displayStatus(): String = if (showInstallOutcome) installMessage() ?: status else status
    fun installMessage(): String? {
        val next = if (apk.isFile) "The downloaded APK is retained. Choose Retry installation or Later."
            else "The downloaded APK is unavailable. Choose Update — download to retry, or Later."
        return when (prefs.getString("install_outcome", "")) {
        "pending" -> "Waiting for Android installer confirmation. Opening an installer is not an installation."
        "cancelled" -> "Installation cancelled. $next"
        "failed" -> "Android rejected the installation. $next"
        "unconfirmed" -> "Installation has not been confirmed. $next"
        "installed" -> "Update installed: Android reports the updated installed version."
        else -> null
        }
    }
    protected fun clearCompletedInstallOutcome() {
        showInstallOutcome = false
        if (!installPending()) prefs.edit().remove("install_outcome").commit()
    }
    fun beginInstall() {
        val expected = release ?: throw IllegalStateException("Cannot save installation attempt")
        kotlin.check(prefs.edit().putLong("install_target_version", expected.versionCode.toLong())
            .putString("install_signer", expected.signingCertificateSha256)
            .putString("install_outcome", "pending").commit()) { "Cannot save installation attempt" }
        retained = true
        showInstallOutcome = true
        status = installMessage() ?: "Waiting for Android installer confirmation."
    }
    protected open fun refreshInstalledVersion(): Boolean = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        installedCode = code(info)
        installedName = info.versionName ?: "(unknown)"
        true
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    protected open fun installedSignerMatches(expected: String): Boolean = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        signers(context.packageManager.getPackageInfo(context.packageName, flags)) == setOf(expected)
    } catch (_: Exception) { false }

    fun reconcileInstall(resultCode: Int?) {
        val target = installTargetVersion()
        if (target <= 0 || resultCode == null && !installPending()) return
        val expectedSigner = prefs.getString("install_signer", null)
        val changed = refreshInstalledVersion() && installedCode >= target &&
            expectedSigner != null && installedSignerMatches(expectedSigner)
        val outcome = when {
            changed -> "installed"
            resultCode == null || resultCode == Activity.RESULT_OK -> "unconfirmed"
            resultCode == Activity.RESULT_CANCELED -> "cancelled"
            else -> "failed"
        }
        prefs.edit().putString("install_outcome", outcome).commit()
        showInstallOutcome = true
        status = installMessage() ?: "Installation has not been confirmed."
        notifyUi()
    }
    fun installerUnavailable() {
        prefs.edit().remove("install_target_version").remove("install_outcome").remove("install_signer").commit()
        showInstallOutcome = false
        status = "Installation was not started. Choose Install update when ready."
        notifyUi()
    }
    open fun attach(value: Observer) { observer = value; notifyUi() }
    open fun detach() { observer = null; cancel() }
    fun detachForRecreation() { observer = null }
    private fun notifyUi() { observer?.changed() }
    open fun foregroundCheck() {
        if (!busy && !installPending() && !installNeedsRetry()) check()
    }
    open fun cancel() {
        finishUpdateRequested = false
        generation++
        client?.cancel()
        executor?.shutdownNow()
        client = null
        executor = null
        if (busy) status = "Cancelled. Check or download again when ready."
        busy = false
        notifyUi()
    }
    protected open fun newClient(): ReleaseClient = ReleaseClient()
    private fun run(message: String, job: (ReleaseClient, Long) -> Unit) {
        if (busy) return
        clearCompletedInstallOutcome()
        busy = true
        status = message
        downloaded = 0
        total = -1
        val ticket = ++generation
        val network = newClient()
        client = network
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "public-release-update") }
        executor = worker
        notifyUi()
        worker.execute {
            try { job(network, ticket) }
            catch (e: Exception) {
                if (e is ReleaseClient.RateLimit)
                    prefs.edit().putLong("backoff_until", System.currentTimeMillis() + e.waitMillis).apply()
                val reason = if (e is java.io.IOException || e is IllegalArgumentException) safeError(e)
                    else "Release metadata or APK could not be verified. Retry."
                if (ticket == generation)
                    android.util.Log.w("ScrcpyAppUpdate", "operation=UPDATE_WORK exception=${e.javaClass.simpleName} reason=$reason")
                post(ticket) { status = reason }
            } finally {
                worker.shutdown()
                post(ticket) { busy = false; client = null; executor = null }
            }
        }
    }
    private fun safeError(e: Exception): String {
        val message = e.message
        val prefixes = listOf("GitHub ", "No public ", "Release is ", "Different signing key", "APK ",
            "Incomplete release", "Release asset", "Download timed", "Invalid release", "Incompatible release",
            "Required release asset", "Duplicate release asset", "Untrusted release URL")
        return message?.takeIf { text -> prefixes.any { text.startsWith(it) } }
            ?: "Update check/download failed. Check internet access and retry."
    }
    private fun post(ticket: Long, action: () -> Unit) {
        main.post { if (ticket == generation) { action(); notifyUi() } }
    }
    open fun check() {
        if (busy) return
        checkSucceeded = false
        clearCompletedInstallOutcome()
        if (System.currentTimeMillis() < prefs.getLong("backoff_until", 0)) {
            status = "GitHub rate limit. Retry after the cooldown (up to one day)."
            android.util.Log.i("ScrcpyAppUpdate", "operation=CHECK deferred=RATE_LIMIT")
            notifyUi()
            return
        }
        if (busy) return
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        run("Checking for updates…") { network, ticket ->
            val api = network.text(endpoint, 512 * 1024)
            val manifestText = network.text(ReleasePolicy.repositoryAssetUrl(api, "update.json", repository), ReleasePolicy.MANIFEST_LIMIT)
            val next = ReleasePolicy.parse(manifestText, context.packageName, asset)
            val url = ReleasePolicy.repositoryAssetUrl(api, asset, repository)
            post(ticket) {
                val previous = release
                if (previous == null || next.versionCode != previous.versionCode ||
                    next.sha256 != previous.sha256 || next.signingCertificateSha256 != previous.signingCertificateSha256) {
                    verified = false; retained = false; apk.delete()
                }
                release = next; apkUrl = url
                checkSucceeded = true
                if (!installPending() && next.versionCode.toLong() != installTargetVersion())
                    prefs.edit().remove("install_target_version").remove("install_outcome").remove("install_signer").apply()
                prefs.edit().putString("manifest", manifestText).putString("apk_url", url)
                    .putLong("last_success", System.currentTimeMillis()).apply()
                status = if (available()) "Update available: ${next.versionName}" else "Installed version is current."
            }
        }
    }
    open fun download() {
        val expected = release ?: return
        val url = apkUrl ?: return
        if (!available() || busy) return
        verified = false; retained = false
        run("Downloading update…") { network, ticket ->
            val partial = File(requireNotNull(apk.parentFile), "download-$ticket.part")
            try {
                var last = 0L
                val hash = network.download(url, partial) { count, length ->
                    val now = System.nanoTime()
                    if (now - last >= 200000000L) {
                        last = now
                        post(ticket) { downloaded = count; total = length }
                    }
                }
                ReleasePolicy.verifyHash(expected.sha256, hash)
                verifyPackage(partial, expected)
                main.post {
                    if (ticket != generation) { partial.delete(); return@post }
                    apk.delete()
                    if (partial.renameTo(apk)) {
                        verified = true; retained = true
                        status = "Verified update ready. Install when casting is stopped."
                    } else status = "Cannot save verified update. Download again."
                    notifyUi()
                }
            } catch (e: Exception) { partial.delete(); throw e }
        }
    }
    open fun verifyForInstall(ready: () -> Unit) {
        val expected = release ?: return
        if (!downloadReady() || !available()) return
        run("Verifying update…") { _, ticket ->
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { input ->
                    val bytes = ByteArray(16384)
                    while (true) {
                        val count = input.read(bytes)
                        if (count == -1) break
                        if (Thread.currentThread().isInterrupted) throw CancellationException()
                        hash.update(bytes, 0, count)
                    }
                }
                ReleasePolicy.verifyHash(expected.sha256, ReleasePolicy.hex(hash.digest()))
                verifyPackage(apk, expected)
                post(ticket) { verified = true; busy = false; ready() }
            } catch (e: Exception) {
                if (e !is CancellationException)
                    post(ticket) { verified = false; retained = false; apk.delete() }
                throw e
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, expected: ReleasePolicy.Manifest) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("APK cannot be read. Download again.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val packageId = candidate.packageName ?: throw IllegalArgumentException("APK package or version mismatch")
        ReleasePolicy.verifyIdentity(expected, packageId, code(candidate), code(installed), signers(candidate), signers(installed))
    }
}

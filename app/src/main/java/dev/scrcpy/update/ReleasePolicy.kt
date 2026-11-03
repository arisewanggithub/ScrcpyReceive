package dev.scrcpy.update

import org.json.JSONObject
import java.net.URI
import java.util.Locale

object ReleasePolicy {
    const val MANIFEST_LIMIT = 64 * 1024
    const val APK_LIMIT = 250L * 1024 * 1024
    private val hosts = setOf("github.com", "api.github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com")

    fun trustedUrl(value: String): String {
        try {
            val uri = URI(value)
            require(value.length <= 4096 && uri.scheme == "https" &&
                uri.rawUserInfo == null && uri.fragment == null &&
                uri.port in setOf(-1, 443) && uri.host in hosts)
            return value
        } catch (_: Exception) {
            throw IllegalArgumentException("Untrusted release URL")
        }
    }

    data class Manifest(
        val applicationId: String,
        val versionName: String,
        val apkAssetName: String,
        val sha256: String,
        val signingCertificateSha256: String,
        val versionCode: Int
    )

    private fun string(json: JSONObject, key: String, max: Int): String {
        val value = json.get(key)
        require(value is String && value.isNotEmpty() && value.length <= max &&
            value.none { it.code < 32 || it.code == 127 ||
                Character.getType(it.code) == Character.FORMAT.toInt() }) { "Invalid release metadata" }
        return value
    }

    private fun integer(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Invalid release number")
    }

    fun parse(text: String, id: String, asset: String): Manifest {
        require(text.toByteArray(Charsets.UTF_8).size <= MANIFEST_LIMIT) { "Release manifest too large" }
        val json = JSONObject(text)
        val code = integer(json, "versionCode")
        require(integer(json, "schema") == 1L && integer(json, "protocolVersion") == 3L &&
            code in 1L..Int.MAX_VALUE.toLong() && id == string(json, "applicationId", 200) &&
            asset == string(json, "apkAssetName", 100)) { "Incompatible release metadata" }
        val hash = string(json, "sha256", 64)
        val cert = string(json, "signingCertificateSha256", 64)
        require(hash.matches(Regex("[0-9a-f]{64}")) && cert.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid release fingerprint"
        }
        return Manifest(id, string(json, "versionName", 80), asset, hash, cert, code.toInt())
    }

    fun assetUrl(json: String, name: String): String {
        val release = JSONObject(json)
        require(release.opt("draft") == false && release.opt("prerelease") == false) { "No stable release" }
        val assets = requireNotNull(release.getJSONArray("assets"))
        var result: String? = null
        for (i in 0 until assets.length()) {
            val asset = requireNotNull(assets.getJSONObject(i))
            if (name == asset.optString("name")) {
                require(result == null) { "Duplicate release asset" }
                result = trustedUrl(string(asset, "browser_download_url", 4096))
            }
        }
        return result ?: throw IllegalArgumentException("Required release asset missing")
    }

    fun repositoryAssetUrl(json: String, name: String, repository: String): String {
        val value = assetUrl(json, name)
        val uri = URI.create(value)
        val path = uri.rawPath ?: throw IllegalArgumentException("Release asset is outside the configured repository")
        require(uri.host == "github.com" && uri.rawQuery == null &&
            path.startsWith("/arisewanggithub/$repository/releases/download/") &&
            path.endsWith("/$name") && !path.contains("%") && !path.contains("/../") &&
            !path.contains("/./")) { "Release asset is outside the configured repository" }
        return value
    }

    fun repositoryReleasePage(apkUrl: String, repository: String, asset: String): String {
        val uri = URI.create(trustedUrl(apkUrl))
        val prefix = "/arisewanggithub/$repository/releases/download/"
        val path = uri.rawPath.orEmpty()
        require(uri.host == "github.com" && uri.rawQuery == null && path.startsWith(prefix) &&
            path.endsWith("/$asset")) { "Release asset is outside the configured repository" }
        val tag = path.removePrefix(prefix).removeSuffix("/$asset")
        require(tag.length <= 80 && tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "Invalid stable release tag"
        }
        return "https://github.com/arisewanggithub/$repository/releases/tag/$tag"
    }

    fun verifyIdentity(manifest: Manifest, packageId: String, archiveCode: Long, installedCode: Long,
        archiveSigners: Set<String>, installedSigners: Set<String>) {
        require(manifest.applicationId == packageId && archiveCode == manifest.versionCode.toLong() &&
            archiveCode > installedCode) { "APK package or version mismatch" }
        // Rotations/multiple signers require a separately reviewed migration.
        require(archiveSigners.size == 1 && installedSigners.size == 1 &&
            archiveSigners == installedSigners && manifest.signingCertificateSha256 in archiveSigners) {
            "Different signing key; cannot update in place. Use the same signing key as the installed app; do not uninstall to update."
        }
    }

    fun verifyHash(expected: String, actual: String) {
        require(expected == actual) { "APK checksum mismatch. Download again." }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
}

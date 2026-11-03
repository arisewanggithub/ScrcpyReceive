package dev.scrcpy.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ReleasePolicyTest {
    private val id = "example.sender"
    private val assetName = "ScrcpySend.apk"
    private val hash = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun manifest(): JSONObject = JSONObject().put("schema", 1).put("applicationId", id)
        .put("versionCode", 2).put("versionName", "2.0").put("apkAssetName", assetName)
        .put("sha256", hash).put("signingCertificateSha256", hash).put("protocolVersion", 3)
    private fun rejected(value: JSONObject) {
        assertThrows(Exception::class.java) { ReleasePolicy.parse(value.toString(), id, assetName) }
    }
    @Test fun contractAndIntegerVersion() {
        val release = ReleasePolicy.parse(manifest().put("versionName", "0.9").toString(), id, assetName)
        assertEquals(2, release.versionCode)
        ReleasePolicy.verifyIdentity(release, id, 2, 1, setOf(hash), setOf(hash))
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.verifyIdentity(release, id, 2, 2, setOf(hash), setOf(hash))
        }
    }
    @Test fun receiverReleaseRequiresRenamedApkWithoutLegacyFallback() {
        val receiverId = "org.screencopy.receiver"
        val receiverAsset = "ScrcpyReceive.apk"
        val value = manifest().put("applicationId", receiverId).put("apkAssetName", receiverAsset)
        assertEquals(receiverAsset, ReleasePolicy.parse(value.toString(), receiverId, receiverAsset).apkAssetName)
        val url = "https://github.com/arisewanggithub/ScrcpyReceive/releases/download/v2/$receiverAsset"
        val api = JSONObject().put("draft", false).put("prerelease", false).put("assets",
            JSONArray().put(JSONObject().put("name", receiverAsset).put("browser_download_url", url)))
        assertEquals(url, ReleasePolicy.repositoryAssetUrl(api.toString(), receiverAsset, "ScrcpyReceive"))
        api.getJSONArray("assets").getJSONObject(0).put("browser_download_url",
            url.replace("/ScrcpyReceive/", "/ScrcpyRecv/"))
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.repositoryAssetUrl(api.toString(), receiverAsset, "ScrcpyReceive")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.parse(value.put("apkAssetName", "ScrcpyRecv.apk").toString(), receiverId, receiverAsset)
        }
        api.put("assets", JSONArray().put(JSONObject().put("name", "ScrcpyRecv.apk")
            .put("browser_download_url", url.replace(receiverAsset, "ScrcpyRecv.apk"))))
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.repositoryAssetUrl(api.toString(), receiverAsset, "ScrcpyReceive")
        }
    }
    @Test fun malformedAndIncompatibleManifests() {
        for (key in listOf("schema", "applicationId", "versionCode", "versionName", "apkAssetName",
            "sha256", "signingCertificateSha256", "protocolVersion")) {
            val missing = manifest().apply { remove(key) }
            rejected(missing)
        }
        for (version in listOf(0, -1, 2147483648L, "2", 2.5, true)) rejected(manifest().put("versionCode", version))
        rejected(manifest().put("schema", 2))
        rejected(manifest().put("protocolVersion", 4))
        rejected(manifest().put("applicationId", "example.other"))
        rejected(manifest().put("apkAssetName", "../ScrcpySend.apk"))
        rejected(manifest().put("sha256", hash.uppercase()))
        rejected(manifest().put("signingCertificateSha256", "abc"))
        rejected(manifest().put("versionName", "bad\nname"))
        rejected(manifest().put("versionName", "\u202Ebad"))
        rejected(manifest().put("versionName", "a".repeat(81)))
        assertThrows(Exception::class.java) { ReleasePolicy.parse("not json", id, assetName) }
        assertThrows(Exception::class.java) { ReleasePolicy.parse(" ".repeat(65537), id, assetName) }
    }
    @Test fun browserReleasePageIsPinnedToTheStableReceiverApk() {
        val base = "https://github.com/arisewanggithub/ScrcpyReceive/releases/download/"
        val asset = "ScrcpyReceive.apk"
        assertEquals("https://github.com/arisewanggithub/ScrcpyReceive/releases/tag/v1.0.6",
            ReleasePolicy.repositoryReleasePage("${base}v1.0.6/$asset", "ScrcpyReceive", asset))
        for (url in listOf(
            "${base}v1.0.6/ScrcpySend.apk",
            "${base}v1.0.6/$asset?redirect=elsewhere",
            "${base}v1.0.6/$asset#fragment",
            "${base}v1.0.6/../v1.0.5/$asset",
            "${base}v1.0.6%2Fother/$asset",
            "${base}v1.0.6-beta/$asset",
            "${base}main/$asset",
            "${base}v1.0.6/$asset".replace("ScrcpyReceive/releases", "ScrcpySend/releases"),
            "${base}v1.0.6/$asset".replace("github.com", "api.github.com"),
            "${base}v1.0.6/$asset".replace("https:", "http:")
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ReleasePolicy.repositoryReleasePage(url, "ScrcpyReceive", asset)
            }
        }
    }
    @Test fun trustedHostsAreExactHttpsOnly() {
        for (host in listOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"))
            assertEquals("https://$host/asset", ReleasePolicy.trustedUrl("https://$host/asset"))
        for (url in listOf("http://github.com/a", "https://github.com.evil.example/a",
            "https://evilgithub.com/a", "https://user@github.com/a", "https://github.com:444/a",
            "file:///a", "https://github.com/a#fragment", "https://raw.githubusercontent.com/a"))
            assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.trustedUrl(url) }
    }
    @Test fun stableReleaseRequiresExactlyNamedUniqueAssets() {
        val url = "https://github.com/example/project/releases/download/v2/ScrcpySend.apk"
        val asset = JSONObject().put("name", assetName).put("browser_download_url", url)
        val api = JSONObject().put("draft", false).put("prerelease", false).put("assets", JSONArray().put(asset))
        assertEquals(url, ReleasePolicy.assetUrl(api.toString(), assetName))
        assertThrows(Exception::class.java) { ReleasePolicy.assetUrl(api.toString(), "update.json") }
        api.put("draft", true)
        assertThrows(Exception::class.java) { ReleasePolicy.assetUrl(api.toString(), assetName) }
        api.put("draft", false).put("prerelease", true)
        assertThrows(Exception::class.java) { ReleasePolicy.assetUrl(api.toString(), assetName) }
        api.put("prerelease", false).put("assets", JSONArray().put(asset).put(asset))
        assertThrows(Exception::class.java) { ReleasePolicy.assetUrl(api.toString(), assetName) }
    }
    @Test fun checksumCannotAuthorizeWrongCertificateOrPackage() {
        val release = ReleasePolicy.parse(manifest().toString(), id, assetName)
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.verifyIdentity(release, id, 2, 1, setOf(hash), setOf(other))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.verifyIdentity(release, "example.other", 2, 1, setOf(hash), setOf(hash))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.verifyIdentity(release, id, 3, 1, setOf(hash), setOf(hash))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.verifyIdentity(release, id, 2, 1, setOf(other), setOf(other))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasePolicy.verifyIdentity(release, id, 2, 1, emptySet(), emptySet())
        }
    }
    @Test fun hashHexUsesLowercase() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ReleasePolicy.hex(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray(Charsets.UTF_8))))
        ReleasePolicy.verifyHash(hash, hash)
        assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.verifyHash(hash, other) }
    }
}

package com.mantra.stopwatch

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * ASKING GITHUB WHETHER THERE IS A NEWER BUILD.
 *
 * The repository is public, so this needs no token and carries none. That matters: an update
 * check is the one thing in an app that reaches the network, and it should be the one thing
 * that could be read over somebody's shoulder without consequence.
 *
 * IT DOES NOT INSTALL ANYTHING. Installing an APK from inside an app needs
 * REQUEST_INSTALL_PACKAGES, which is a permission that lets an app put software on a phone
 * without being asked again — a serious thing to hold for a stopwatch, and a serious thing for
 * anybody reading the manifest to have to take on trust. So this hands the download URL to
 * Android, which downloads it and offers to install it with its own dialogue and its own
 * warnings. One more tap, and the phone stays in charge of what gets installed on it.
 */
object UpdateCheck {

    fun check(current: Int, onState: (UpdateState) -> Unit) {
        onState(UpdateState.Checking)
        thread(name = "update-check", isDaemon = true) {
            val connection = try {
                (URL(Updates.LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    // Bounded, because a check that hangs is a control that never answers and a
                    // person who presses it again.
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
            } catch (e: Exception) {
                onState(UpdateState.Failed("no network"))
                return@thread
            }

            try {
                if (connection.responseCode != 200) {
                    // The code rather than a shrug: 403 is a rate limit and 404 is a repository
                    // that has moved, and those want different things done about them.
                    onState(UpdateState.Failed("github " + connection.responseCode))
                    return@thread
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", null)
                val assets = json.optJSONArray("assets")
                val url = (0 until (assets?.length() ?: 0))
                    .map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
                onState(Updates.compare(current, tag, url?.takeIf { it.isNotBlank() }))
            } catch (e: Exception) {
                onState(UpdateState.Failed("bad answer"))
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Hands the URL to Android. It downloads, and its own installer takes it from there. */
    fun download(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

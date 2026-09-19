package com.hyperisland.root.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.hyperisland.root.util.CrashLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches [Notification.Action] arrays keyed by [StatusBarNotification.getKey]
 * so the Island overlay can fire real system actions (including RemoteInput
 * inline reply) without holding PendingIntents inside Compose state.
 *
 * Populated by [IslandNotificationListener]; consumed by
 * [com.hyperisland.root.service.IslandOverlayService] via action strings.
 */
object NotificationActionStore {

    data class ActionInfo(
        val title: String,
        val index: Int,
        /** True when this action has at least one RemoteInput (reply field). */
        val isReply: Boolean,
        val remoteInputKey: String? = null
    )

    private data class Entry(
        val actions: Array<Notification.Action>,
        val infos: List<ActionInfo>,
        val contentIntent: PendingIntent?
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun put(
        key: String,
        actions: Array<Notification.Action>?,
        contentIntent: PendingIntent? = null
    ) {
        val safeActions = actions ?: emptyArray()
        if (safeActions.isEmpty() && contentIntent == null) {
            cache.remove(key)
            return
        }
        val infos = safeActions.mapIndexedNotNull { index, action ->
            val title = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val remoteInputs = action.remoteInputs
            val isReply = !remoteInputs.isNullOrEmpty()
            val resultKey = remoteInputs?.firstOrNull()?.resultKey
            ActionInfo(title = title, index = index, isReply = isReply, remoteInputKey = resultKey)
        }
        cache[key] = Entry(safeActions, infos, contentIntent)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun getInfos(key: String): List<ActionInfo> = cache[key]?.infos ?: emptyList()

    /**
     * Open the notification's content intent (same as tapping a normal heads-up).
     * Falls back to launching the package launcher activity when no contentIntent exists.
     */
    fun openContent(context: Context, key: String, packageName: String? = null): Boolean {
        val entry = cache[key]
        val pi = entry?.contentIntent
        if (pi != null) {
            return try {
                pi.send()
                CrashLogger.i("NotificationActionStore: opened contentIntent for $key")
                true
            } catch (t: Throwable) {
                CrashLogger.e("NotificationActionStore: contentIntent send failed", t)
                launchPackage(context, packageName)
            }
        }
        return launchPackage(context, packageName)
    }

    private fun launchPackage(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                CrashLogger.i("NotificationActionStore: launched package $packageName")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            CrashLogger.e("NotificationActionStore: launch package failed", t)
            false
        }
    }

    /**
     * Fire a non-reply action (Mark as read, Mute, etc.) via its PendingIntent.
     * @return true if the intent was sent successfully
     */
    fun sendAction(context: Context, key: String, index: Int): Boolean {
        val entry = cache[key] ?: run {
            CrashLogger.w("NotificationActionStore: no cache for key=$key")
            return false
        }
        if (index !in entry.actions.indices) return false
        val action = entry.actions[index]
        return try {
            action.actionIntent.send()
            CrashLogger.i("NotificationActionStore: sent action[$index] '${action.title}' for $key")
            true
        } catch (t: Throwable) {
            CrashLogger.e("NotificationActionStore: action send failed", t)
            false
        }
    }

    /**
     * Fire a RemoteInput reply with [text].
     * @return true if the reply intent was sent successfully
     */
    fun sendReply(context: Context, key: String, index: Int, text: String): Boolean {
        val entry = cache[key] ?: run {
            CrashLogger.w("NotificationActionStore: no cache for reply key=$key")
            return false
        }
        if (index !in entry.actions.indices) return false
        val action = entry.actions[index]
        val remoteInputs = action.remoteInputs
        if (remoteInputs.isNullOrEmpty()) {
            // Fallback: treat as normal action if no RemoteInput
            return sendAction(context, key, index)
        }
        return try {
            val intent = Intent()
            val results = Bundle()
            for (ri in remoteInputs) {
                results.putCharSequence(ri.resultKey, text)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            // Some apps also need the clip-data form used by Wear / inline reply
            try {
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            } catch (_: Throwable) { /* older API */ }

            action.actionIntent.send(context, 0, intent)
            CrashLogger.i("NotificationActionStore: sent reply[$index] for $key textLen=${text.length}")
            true
        } catch (t: PendingIntent.CanceledException) {
            CrashLogger.e("NotificationActionStore: reply PendingIntent canceled", t)
            false
        } catch (t: Throwable) {
            CrashLogger.e("NotificationActionStore: reply send failed", t)
            false
        }
    }
}

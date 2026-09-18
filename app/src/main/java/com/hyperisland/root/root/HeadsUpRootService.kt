package com.hyperisland.root.root

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * Runs in a genuine root process (started by libsu's [RootService] via
 * `su`), not the app's normal process. This matters because changing
 * *another* app's notification channel importance requires the caller's
 * UID to actually be root/system — a normal app process piping commands
 * through `Shell.cmd()` (as [RootManager] does for simple one-shot shell
 * commands) still runs the *command* as root, but the app process itself
 * stays a regular app UID, and `cmd notification` doesn't expose an
 * importance-setting subcommand at all (see [HeadsUpSuppressor] doc).
 *
 * The one real way to do this is the same one
 * `cmd notification set_bubbles_channel` uses internally in AOSP:
 * `INotificationManager.getNotificationChannel()` +
 * `NotificationChannel.setImportance()` +
 * `INotificationManager.updateNotificationChannelForPackage()`. Those are
 * hidden/system APIs — calling them from here works because this whole
 * process's UID is root, so it passes the same caller checks a real `adb
 * shell cmd notification ...` invocation would.
 *
 * Bound to from [HeadsUpSuppressor] via `RootService.bind(...)`. See
 * libsu's RootService docs — this process is separate from the app's, so
 * only the AIDL surface in [IHeadsUpControl] crosses the boundary; nothing
 * else in this class is reachable from the app process.
 */
class HeadsUpRootService : RootService() {

    override fun onBind(intent: Intent): IBinder = Stub()

    private class Stub : IHeadsUpControl.Stub() {

        override fun getImportance(packageName: String, channelId: String): Int {
            return try {
                val channel = fetchChannel(packageName, channelId) ?: return Int.MIN_VALUE
                val getImportanceMethod = channel.javaClass.getMethod("getImportance")
                getImportanceMethod.invoke(channel) as Int
            } catch (t: Throwable) {
                Int.MIN_VALUE
            }
        }

        override fun setImportance(packageName: String, channelId: String, importance: Int): Boolean {
            return try {
                val channel = fetchChannel(packageName, channelId) ?: return false
                val setImportanceMethod = channel.javaClass.getMethod(
                    "setImportance", Int::class.javaPrimitiveType
                )
                setImportanceMethod.invoke(channel, importance)

                val appUid = resolveUid(packageName) ?: return false
                val notificationManager = notificationManagerBinder() ?: return false
                val updateMethod = notificationManager.javaClass.getMethod(
                    "updateNotificationChannelForPackage",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Class.forName("android.app.NotificationChannel")
                )
                updateMethod.invoke(notificationManager, packageName, appUid, channel)
                true
            } catch (t: Throwable) {
                false
            }
        }

        override fun listChannels(packageName: String): MutableList<String> {
            val result = mutableListOf<String>()
            try {
                val notificationManager = notificationManagerBinder() ?: return result
                val appUid = resolveUid(packageName) ?: return result

                // INotificationManager.getNotificationChannelsForPackage(
                //     String pkg, int uid, boolean includeDeleted)
                val getChannelsMethod = notificationManager.javaClass.getMethod(
                    "getNotificationChannelsForPackage",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                val parceledListSlice = getChannelsMethod.invoke(
                    notificationManager, packageName, appUid, false
                )
                val getListMethod = parceledListSlice.javaClass.getMethod("getList")
                @Suppress("UNCHECKED_CAST")
                val channels = getListMethod.invoke(parceledListSlice) as List<Any>

                for (channel in channels) {
                    val id = channel.javaClass.getMethod("getId").invoke(channel) as String
                    val importance = channel.javaClass.getMethod("getImportance")
                        .invoke(channel) as Int
                    result.add("$id:$importance")
                }
            } catch (t: Throwable) {
                // Return whatever we gathered so far (usually empty); the
                // caller treats an empty list as "couldn't read channels".
            }
            return result
        }

        // --- shared helpers -------------------------------------------------

        private fun notificationManagerBinder(): Any? {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "notification") as? IBinder ?: return null
            val stubClass = Class.forName("android.app.INotificationManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            return asInterfaceMethod.invoke(null, binder)
        }

        private fun packageManagerBinder(): Any? {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "package") as? IBinder ?: return null
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            return asInterfaceMethod.invoke(null, binder)
        }

        private fun resolveUid(packageName: String): Int? {
            return try {
                val pm = packageManagerBinder() ?: return null
                // IPackageManager.getPackageUid(String packageName, long flags, int userId)
                val getUidMethod = pm.javaClass.getMethod(
                    "getPackageUid",
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                val uid = getUidMethod.invoke(pm, packageName, 0L, 0) as Int
                if (uid > 0) uid else null
            } catch (t: Throwable) {
                null
            }
        }

        private fun fetchChannel(packageName: String, channelId: String): Any? {
            val notificationManager = notificationManagerBinder() ?: return null
            // INotificationManager.getNotificationChannel(
            //     String callingPkg, int userId, String targetPkg, String channelId)
            val getChannelMethod = notificationManager.javaClass.getMethod(
                "getNotificationChannel",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            return getChannelMethod.invoke(
                notificationManager,
                "android", // calling package: root process has no app identity of its own
                0,         // primary user; suppression UI doesn't offer multi-user selection
                packageName,
                channelId
            )
        }
    }
}

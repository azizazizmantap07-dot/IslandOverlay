// AIDL interface implemented by HeadsUpRootService and running inside the
// root process started by libsu's RootService. HeadsUpSuppressor (running
// in the normal app process) binds to it and calls these methods over
// Binder IPC, since downgrading another package's notification channel
// importance requires a system/root-level caller — a normal app process
// (even with a root *shell* available via libsu's Shell.cmd) cannot call
// the hidden INotificationManager APIs directly, only a process whose
// actual UID is root/system can.
package com.hyperisland.root.root;

interface IHeadsUpControl {

    // Returns the channel's importance as reported by
    // INotificationManager.getNotificationChannel(), or Integer.MIN_VALUE
    // if the channel could not be read (channel doesn't exist yet, app
    // never posted, etc).
    int getImportance(String packageName, String channelId);

    // Sets the channel's importance via
    // INotificationManager.updateNotificationChannelForPackage().
    // Returns true on success, false otherwise (never throws — callers
    // get a boolean instead of having to catch RemoteException-wrapped
    // reflection failures).
    boolean setImportance(String packageName, String channelId, int importance);

    // Lists channel ids + their current importance for packageName, by
    // calling INotificationManager.getNotificationChannelsForPackage()
    // (the real API — this replaces the old, broken `dumpsys notification`
    // text-scraping approach in HeadsUpSuppressor.listChannels()).
    // Returned as "channelId:importance" pairs since AIDL doesn't support
    // Kotlin Pair/data classes without a parcelable wrapper.
    List<String> listChannels(String packageName);
}

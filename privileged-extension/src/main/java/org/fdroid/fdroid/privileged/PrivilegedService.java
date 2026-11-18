/*
 * Copyright (C) 2015-2016 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fdroid.fdroid.privileged;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * This service provides an API via AIDL IPC for the main F-Droid app to install/delete packages.
 * <p>
 * Changes compared to version 0.2.13 (which this is based on):
 * <ul>
 * <li>the callback is stored before the install is committed, so a quick result can't get lost</li>
 * <li>every failure is reported to the client instead of crashing the service or staying silent</li>
 * <li>a missing status extra is treated as failure (the old default was the code for success)</li>
 * <li>toasts are shown on the main thread (binder threads have no Looper)</li>
 * <li>code for Android older than 7.0 and its hidden API stubs were removed (minSdk is 24)</li>
 * </ul>
 */
public class PrivilegedService extends Service {

    public static final String TAG = "PrivilegedExtension";
    private static final String BROADCAST_ACTION_INSTALL =
            "org.fdroid.fdroid.PrivilegedExtension.ACTION_INSTALL_COMMIT";
    private static final String BROADCAST_ACTION_UNINSTALL =
            "org.fdroid.fdroid.PrivilegedExtension.ACTION_UNINSTALL_COMMIT";
    private static final String BROADCAST_SENDER_PERMISSION =
            "android.permission.INSTALL_PACKAGES";
    private static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";

    /** Legacy PackageManager.INSTALL_FAILED_INTERNAL_ERROR */
    private static final int INSTALL_FAILED_INTERNAL_ERROR = -110;
    /** Legacy PackageManager.DELETE_FAILED_INTERNAL_ERROR */
    private static final int DELETE_FAILED_INTERNAL_ERROR = -1;

    private AccessProtectionHelper accessProtectionHelper;

    private volatile IPrivilegedCallback mCallback;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean hasPrivilegedPermissionsImpl() {
        boolean hasInstallPermission =
                getPackageManager().checkPermission(Manifest.permission.INSTALL_PACKAGES, getPackageName())
                        == PackageManager.PERMISSION_GRANTED;
        boolean hasDeletePermission =
                getPackageManager().checkPermission(Manifest.permission.DELETE_PACKAGES, getPackageName())
                        == PackageManager.PERMISSION_GRANTED;

        return hasInstallPermission && hasDeletePermission;
    }

    private void showToast(final String message) {
        if (message == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PrivilegedService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Tells the client how it went. Never throws.
     */
    private void reportResult(String packageName, int returnCode) {
        final IPrivilegedCallback callback = mCallback;
        if (callback == null) {
            Log.w(TAG, "No callback for result " + returnCode + " of " + packageName);
            return;
        }
        try {
            callback.handleResult(packageName, returnCode);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error reporting result", e);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final boolean uninstall = BROADCAST_ACTION_UNINSTALL.equals(intent.getAction());
            // The default must be a failure. The legacy code for success is 1, which is the same
            // number as PackageInstaller.STATUS_FAILURE, so that can't be used as default.
            final int returnCode = intent.getIntExtra(EXTRA_LEGACY_STATUS,
                    uninstall ? DELETE_FAILED_INTERNAL_ERROR : INSTALL_FAILED_INTERNAL_ERROR);
            final String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            reportResult(packageName, returnCode);
        }
    };

    /**
     * Below function is copied mostly as-is from
     * https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/06163dec5a23bb3f17f7e6279f6d46e1851b7d16
     */
    private void doPackageStage(Uri packageURI) throws IOException {
        final PackageManager pm = getPackageManager();
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        final PackageInstaller packageInstaller = pm.getPackageInstaller();
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            final int sessionId = packageInstaller.createSession(params);
            final byte[] buffer = new byte[65536];
            session = packageInstaller.openSession(sessionId);
            final InputStream in = getContentResolver().openInputStream(packageURI);
            if (in == null) {
                throw new IOException("Could not open " + packageURI);
            }
            final OutputStream out = session.openWrite("PackageInstaller", 0, -1 /* sizeBytes, unknown */);
            try {
                int c;
                while ((c = in.read(buffer)) != -1) {
                    out.write(buffer, 0, c);
                }
                session.fsync(out);
            } finally {
                IoUtils.closeQuietly(in);
                IoUtils.closeQuietly(out);
            }
            // Create a PendingIntent and use it to generate the IntentSender
            Intent broadcastIntent = new Intent(BROADCAST_ACTION_INSTALL);
            broadcastIntent.setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this /*context*/,
                    sessionId,
                    broadcastIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pendingIntent.getIntentSender());
            committed = true;
        } finally {
            if (session != null) {
                if (!committed) {
                    try {
                        session.abandon();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Could not abandon session", e);
                    }
                }
                IoUtils.closeQuietly(session);
            }
        }
    }

    private final IPrivilegedService.Stub binder = new IPrivilegedService.Stub() {
        @Override
        public boolean hasPrivilegedPermissions() {
            boolean callerIsAllowed = accessProtectionHelper.isCallerAllowed();
            return callerIsAllowed && hasPrivilegedPermissionsImpl();
        }

        @Override
        public void installPackage(Uri packageURI, int flags, String installerPackageName,
                                   IPrivilegedCallback callback) {
            if (!accessProtectionHelper.isCallerAllowed()) {
                return;
            }
            // Store the callback first, the result can arrive as soon as the session is committed.
            mCallback = callback;
            try {
                doPackageStage(packageURI);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Install failed", e);
                showToast(e.getLocalizedMessage());
                reportResult(null, INSTALL_FAILED_INTERNAL_ERROR);
            }
        }

        @Override
        public void deletePackage(String packageName, int flags, IPrivilegedCallback callback) {
            if (!accessProtectionHelper.isCallerAllowed()) {
                return;
            }
            mCallback = callback;
            try {
                final PackageManager pm = getPackageManager();
                final PackageInstaller packageInstaller = pm.getPackageInstaller();

                /*
                 * The client app used to set this to F-Droid, but we need it to be set to
                 * this package's package name to be able to uninstall from here.
                 */
                try {
                    pm.setInstallerPackageName(packageName, getPackageName());
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not set installer package name", e);
                }
                // Create a PendingIntent and use it to generate the IntentSender
                Intent broadcastIntent = new Intent(BROADCAST_ACTION_UNINSTALL);
                broadcastIntent.setPackage(getPackageName());
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        PrivilegedService.this, // context
                        0, // arbitary
                        broadcastIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                packageInstaller.uninstall(packageName, pendingIntent.getIntentSender());
            } catch (RuntimeException e) {
                Log.e(TAG, "Uninstall failed", e);
                reportResult(packageName, DELETE_FAILED_INTERNAL_ERROR);
            }
        }

        @Override
        public List<PackageInfo> getInstalledPackages(int flags) {
            if (Build.VERSION.SDK_INT >= 29) {
                Integer matchStaticSharedLibraries = getMatchStaticSharedLibraries();
                if (matchStaticSharedLibraries != null) {
                    flags |= matchStaticSharedLibraries;
                }
            }
            return getPackageManager().getInstalledPackages(flags);
        }
    };

    /**
     * Get private constant.
     *
     * @see <a href="https://github.com/aosp-mirror/platform_frameworks_base/blob/android-11.0.0_r31/core/java/android/content/pm/PackageManager.java#L530">PackageManager.MATCH_STATIC_SHARED_LIBRARIES</a>
     */
    protected static Integer getMatchStaticSharedLibraries() {
        try {
            java.lang.reflect.Field field = PackageManager.class.getDeclaredField("MATCH_STATIC_SHARED_LIBRARIES");
            return (Integer) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // blocked by the hidden API restrictions on newer Android versions, then we go without
            Log.w(TAG, "MATCH_STATIC_SHARED_LIBRARIES not available", e);
        }
        return null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        accessProtectionHelper = new AccessProtectionHelper(this);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BROADCAST_ACTION_INSTALL);
        intentFilter.addAction(BROADCAST_ACTION_UNINSTALL);
        registerReceiver(
                mBroadcastReceiver, intentFilter, BROADCAST_SENDER_PERMISSION, null /*scheduler*/);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mBroadcastReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Receiver was not registered", e);
        }
    }

}

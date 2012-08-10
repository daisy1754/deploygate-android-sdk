
package com.deploygate.sdk;

import android.Manifest.permission;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.deploygate.service.DeployGateEvent;
import com.deploygate.service.IDeployGateSdkService;
import com.deploygate.service.IDeployGateSdkServiceCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * DeployGate SDK library implementation. Import this library to the application
 * package and call {@link #install(Application)} on the onCreate() of
 * application class to enable crash reporting and application launch
 * notification.
 * <p>
 * In order to get working Remote LogCat feature, you also have to add
 * <code>&lt;uses-permission android:name="android.permission.READ_LOGS" /&gt;</code>
 * in AndroidManifest.xml of your application.
 * </p>
 * 
 * @author tnj
 */
public class DeployGate {

    private static final String TAG = "DeployGate";

    private static final String ACTION_DEPLOYGATE_STARTED = "com.deploygate.action.ServiceStarted";
    private static final String DEPLOYGATE_PACKAGE = "com.deploygate";

    private static final String[] DEPLOYGATE_FINGERPRINTS = new String[] {
            "c1f285f69cc02a397135ed182aa79af53d5d20a1", // mba debug
            "234eff4a1600a7aa78bf68adfbb15786e886ae1a", // jenkins debug
    };

    private static DeployGate sInstance;

    private final Context mApplicationContext;
    private final Handler mHandler;
    private final DeployGateCallback mCallback;

    private CountDownLatch mInitializedLatch;
    private boolean mIsDeployGateAvailable;

    private boolean mAppIsManaged;
    private boolean mAppIsAuthorized;
    private boolean mAppIsStopRequested;
    private String mLoginUsername;

    private IDeployGateSdkService mRemoteService;
    private Thread mLogcatThread;
    private LogCatTranportWorker mLogcatWorker;

    private final IDeployGateSdkServiceCallback mRemoteCallback = new IDeployGateSdkServiceCallback.Stub() {

        public void onEvent(String action, Bundle extras) throws RemoteException {
            if (DeployGateEvent.ACTION_INIT.equals(action)) {
                onInitialized(extras.getBoolean(DeployGateEvent.EXTRA_IS_MANAGED, false),
                        extras.getBoolean(DeployGateEvent.EXTRA_IS_AUTHORIZED, false),
                        extras.getString(DeployGateEvent.EXTRA_LOGIN_USERNAME),
                        extras.getBoolean(DeployGateEvent.EXTRA_IS_STOP_REQUESTED, false));
            }
            else if (DeployGateEvent.ACTION_UPDATE_AVAILABLE.equals(action)) {
                onUpdateArrived(extras.getInt(DeployGateEvent.EXTRA_SERIAL),
                        extras.getString(DeployGateEvent.EXTRA_VERSION_NAME),
                        extras.getInt(DeployGateEvent.EXTRA_VERSION_CODE));
            }
            else if (DeployGateEvent.ACTION_ENABLE_LOGCAT.equals(action)) {
                onEnableLogcat(true);
            }
            else if (DeployGateEvent.ACTION_DISABLE_LOGCAT.equals(action)) {
                onEnableLogcat(false);
            }
        };

        private void onEnableLogcat(boolean isEnabled) {
            if (mRemoteService == null)
                return;

            if (isEnabled) {
                if (mLogcatThread == null || !mLogcatThread.isAlive()) {
                    mLogcatWorker = new LogCatTranportWorker(
                            mApplicationContext.getPackageName(), mRemoteService);
                    mLogcatThread = new Thread(mLogcatWorker);
                    mLogcatThread.start();
                }
            } else {
                if (mLogcatThread != null && mLogcatThread.isAlive()) {
                    mLogcatWorker.stop();
                    mLogcatThread.interrupt();
                }
            }
        }

        private void onInitialized(final boolean isManaged, final boolean isAuthorized,
                final String loginUsername, final boolean isStopped) throws RemoteException {
            Log.v(TAG, "DeployGate service initialized");
            mAppIsManaged = isManaged;
            mAppIsAuthorized = isAuthorized;
            mAppIsStopRequested = isStopped;
            mLoginUsername = loginUsername;

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mCallback != null) {
                        mCallback.onInitialized(true);
                        mCallback
                                .onStatusChanged(isManaged, isAuthorized, loginUsername, isStopped);
                    }
                }
            });

            mIsDeployGateAvailable = true;
            mInitializedLatch.countDown();
        }

        private void onUpdateArrived(final int serial, final String versionName,
                final int versionCode) throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mCallback != null)
                        mCallback.onUpdateAvailable(serial, versionName, versionCode);
                }
            });
        }
    };

    /**
     * Do not instantiate directly. Call {@link #install(Application)} on your
     * {@link Application#onCreate()} instead.
     */
    private DeployGate(Context applicationContext, DeployGateCallback callback) {
        mHandler = new Handler();
        mApplicationContext = applicationContext;
        mCallback = callback;
        mInitializedLatch = new CountDownLatch(1);

        prepareBroadcastReceiver();
        if (isDeployGateAvailable()) {
            Log.v(TAG, "DeployGate installation detected. Initializing.");
            bindToService(true);
        } else {
            Log.v(TAG, "DeployGate is not available on this device.");
            mInitializedLatch.countDown();
            mIsDeployGateAvailable = false;
            if (mCallback != null)
                mCallback.onInitialized(false);
        }
    }

    private boolean isDeployGateAvailable() {
        String sig = getDeployGatePackageSignature();
        if (sig == null)
            return false;
        for (String value : DEPLOYGATE_FINGERPRINTS)
            if (value.equals(sig))
                return true;
        return false;
    }

    private void prepareBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_DEPLOYGATE_STARTED);
        mApplicationContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null)
                    return;
                if (isDeployGateAvailable()) {
                    bindToService(false);
                }
            }
        }, filter);
    }

    private void bindToService(final boolean isBoot) {
        Intent service = new Intent(IDeployGateSdkService.class.getName());
        service.setPackage(DEPLOYGATE_PACKAGE);
        mApplicationContext.bindService(service, new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.v(TAG, "DeployGate service connected");
                mRemoteService = IDeployGateSdkService.Stub.asInterface(service);

                Bundle args = new Bundle();
                args.putBoolean(DeployGateEvent.EXTRA_IS_BOOT, isBoot);
                args.putBoolean(DeployGateEvent.EXTRA_CAN_LOGCAT, canLogCat());
                try {
                    mRemoteService.init(mRemoteCallback, mApplicationContext.getPackageName(), args);
                } catch (RemoteException e) {
                    Log.w(TAG, "DeployGate service failed to be initialized.");
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.v(TAG, "DeployGate service disconneced");
                mRemoteService = null;
            }
        }, Context.BIND_AUTO_CREATE);
    }

    protected boolean canLogCat() {
        return mApplicationContext.getPackageManager().checkPermission(permission.READ_LOGS,
                mApplicationContext.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    private String getDeployGatePackageSignature() {
        PackageInfo info;
        try {
            info = mApplicationContext.getPackageManager().getPackageInfo(
                    DEPLOYGATE_PACKAGE, PackageManager.GET_SIGNATURES);
        } catch (NameNotFoundException e) {
            return null;
        }
        if (info == null || info.signatures.length == 0)
            return null;

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA1 is not supported on this platform?", e);
            return null;
        }

        byte[] digest = md.digest(info.signatures[0].toByteArray());
        StringBuilder result = new StringBuilder(40);
        for (int i = 0; i < digest.length; i++) {
            result.append(Integer.toString((digest[i] & 0xff) + 0x100, 16).substring(1));
        }
        return result.toString();
    }

    /**
     * Install DeployGate on your application instance. Call this method inside
     * of your {@link Application#onCreate()}.
     * 
     * @param app Application instance, typically just pass <em>this<em>.
     */
    public static void install(Application app) {
        install(app, null);
    }

    /**
     * Install DeployGate on your application instance. Call this method inside
     * of your {@link Application#onCreate()}.
     * 
     * @param app Application instance, typically just pass <em>this<em>.
     * @param callback Callback interface to listen events.
     */
    public static void install(Application app, DeployGateCallback callback) {
        if (sInstance == null) {
            Thread.setDefaultUncaughtExceptionHandler(new DeployGateUncaughtExceptionHandler(Thread
                    .getDefaultUncaughtExceptionHandler()));
            sInstance = new DeployGate(app.getApplicationContext(), callback);
        }
    }

    /**
     * Get whether SDK is completed its intialization process and ready after
     * {@link #install(Application)}. This call will never blocked.
     * 
     * @return true if SDK is ready. false otherwise. If no install() called
     *         ever, this always returns false.
     */
    public static boolean isInitialized() {
        if (sInstance != null) {
            return sInstance.mInitializedLatch.getCount() == 0;
        }
        return false;
    }

    /**
     * Get whether DeployGate client service is available on this device.
     * <p>
     * Note this function will block until SDK get ready after
     * {@link #install(Application)} called. So if you want to call this
     * function from the main thread, you should confirm that
     * {@link #isInitialized()} is true before calling this. (Or consider using
     * {@link DeployGateCallback#onInitialized(boolean)} callback.)
     * </p>
     * 
     * @return true if valid DeployGate client is available. false otherwise. If
     *         no install() called ever, this always returns false.
     */
    public static boolean isDeployGateAvaliable() {
        if (sInstance != null) {
            waitForInitialized();
            return sInstance.mIsDeployGateAvailable;
        }
        return false;
    }

    /**
     * Get whether this application and its package is known and managed under
     * the DeployGate.
     * <p>
     * Note this function will block until SDK get ready after
     * {@link #install(Application)} called. So if you want to call this
     * function from the main thread, you should confirm that
     * {@link #isInitialized()} is true before calling this. (Or consider using
     * {@link DeployGateCallback#onInitialized(boolean)} callback.)
     * </p>
     * 
     * @return true if DeployGate knows and manages this package. false
     *         otherwise. If no install() called ever, this always returns
     *         false.
     */
    public static boolean isManaged() {
        if (sInstance != null) {
            waitForInitialized();
            return sInstance.mAppIsManaged;
        }
        return false;
    }

    /**
     * Get whether current DeployGate user has this application in his/her
     * available list. You may want to check this value on initialization
     * process of the main activity if you want to limit user of this
     * application to only who you explicitly allowed.
     * <p>
     * Note this function will block until SDK get ready after
     * {@link #install(Application)} called. So if you want to call this
     * function from the main thread, you should confirm that
     * {@link #isInitialized()} is true before calling this. (Or consider using
     * {@link DeployGateCallback#onInitialized(boolean)} callback.)
     * </p>
     * 
     * @return true if current DeployGate user has available list which contains
     *         this application. false otherwise. If no install() called ever,
     *         this always returns false.
     */
    public static boolean isAuthorized() {
        if (sInstance != null) {
            waitForInitialized();
            return sInstance.mAppIsAuthorized;
        }
        return false;
    }

    /**
     * Get current DeployGate username.
     * <p>
     * Note this function will block until SDK get ready after
     * {@link #install(Application)} called. So if you want to call this
     * function from the main thread, you should confirm that
     * {@link #isInitialized()} is true before calling this. (Or consider using
     * {@link DeployGateCallback#onInitialized(boolean)} callback.)
     * </p>
     * 
     * @return true if current DeployGate user has available list which contains
     *         this application. false otherwise. If no install() called ever,
     *         this always returns false.
     */
    public static String getLoginUsername() {
        if (sInstance != null) {
            waitForInitialized();
            return sInstance.mLoginUsername;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private/* public */static boolean isStopRequested() {
        if (sInstance != null) {
            waitForInitialized();
            return sInstance.mAppIsStopRequested;
        }
        return false;
    }

    private static void waitForInitialized() {
        try {
            sInstance.mInitializedLatch.await();
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted while waiting initialization");
        }
    }

    private static class LogCatTranportWorker implements Runnable {
        private final String mPackageName;
        private final IDeployGateSdkService mService;
        private Process mProcess;

        public LogCatTranportWorker(String packageName, IDeployGateSdkService service) {
            mPackageName = packageName;
            mService = service;
        }

        @Override
        public void run() {
            mProcess = null;
            ArrayList<String> logcatBuf = null;
            try {
                ArrayList<String> commandLine = new ArrayList<String>();
                commandLine.add("logcat");
                logcatBuf = new ArrayList<String>();

                commandLine.add("-v");
                commandLine.add("threadtime");
                commandLine.add("*:V");

                mProcess = Runtime.getRuntime().exec(
                        commandLine.toArray(new String[commandLine.size()]));
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                        mProcess.getInputStream()));

                Log.v(TAG, "Start retrieving logcat");
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    logcatBuf.add(line + "\n");
                    if (!bufferedReader.ready()) {
                        if (send(logcatBuf))
                            logcatBuf.clear();
                        else
                            return;
                    }
                }
                // EOF, stop it
            } catch (IOException e) {
                Log.d(TAG, "Logcat stopped: " + e.getMessage());
            } finally {
                if (mProcess != null)
                    mProcess.destroy();
            }
        }

        public void stop() {
            if (mProcess != null)
                mProcess.destroy();
        }

        private boolean send(ArrayList<String> logcatBuf) {
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(DeployGateEvent.EXTRA_LOG, logcatBuf);
            try {
                mService.sendEvent(mPackageName, DeployGateEvent.ACTION_SEND_LOGCAT, bundle);
            } catch (RemoteException e) {
                return false;
            }
            return true;
        }
    }

    static DeployGate getInstance() {
        return sInstance;
    }

    void sendCrashReport(Throwable ex) {
        if (mRemoteService == null)
            return;
        Bundle extras = new Bundle();
        extras.putSerializable(DeployGateEvent.EXTRA_EXCEPTION, ex);
        try {
            mRemoteService.sendEvent(mApplicationContext.getPackageName(),
                    DeployGateEvent.ACTION_SEND_CRASH_REPORT, extras);
        } catch (RemoteException e) {
            Log.w(TAG, "failed to send crash report: " + e.getMessage());
        }
    }
}
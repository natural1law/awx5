package com.just.x5.downFile;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;

import com.just.x5.IProvider;
import com.just.x5.PermissionConstant;
import com.just.x5.R;
import com.just.x5.permission.IPermissionInterceptor;
import com.just.x5.util.AgentWebX5Utils;
import com.just.x5.util.LogUtils;
import com.permission.kit.OnPermissionListener;
import com.permission.kit.PermissionKit;
import com.tencent.smtt.sdk.DownloadListener;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认下载实现
 */

public class DefaultDownLoaderImpl implements DownloadListener, DownLoadResultListener {
    private Context mContext;
    private boolean isForce;
    private boolean enableIndicator;
    private int NotificationID = 1;
    private List<DownLoadResultListener> mDownLoadResultListeners;
    private WeakReference<Activity> mActivityWeakReference;
    private DownLoadMsgConfig mDownLoadMsgConfig;
    private static final String TAG = DefaultDownLoaderImpl.class.getSimpleName();
    private AtomicBoolean isParallelDownload = new AtomicBoolean(false);
    private int icon;

    private DefaultDownLoaderImpl(Builder builder) {
        mActivityWeakReference = new WeakReference<>(builder.mActivity);
        this.mContext = builder.mActivity.getApplicationContext();
        this.isForce = builder.isForce;
        this.enableIndicator = builder.enableIndicator;
        this.mDownLoadResultListeners = builder.mDownLoadResultListeners;
        this.mDownLoadMsgConfig = builder.mDownLoadMsgConfig;
        isParallelDownload.set(builder.isParallelDownload);
        icon = builder.icon;
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public synchronized void onDownloadStart(final String url, String userAgent, final String contentDisposition, String mimeType, final long contentLength) {
        PermissionKit.getInstance().requestPermission(mActivityWeakReference.get(), 52 >> 6, new OnPermissionListener() {
            @Override
            public void onSuccess(int requestCode, String... permissions) {
                preDownload(url, contentDisposition, contentLength);
            }

            @Override
            public void onFail(int requestCode, String... permissions) {
                //授权失败后再次操作
                PermissionKit.getInstance().guideSetting(mActivityWeakReference.get(), true, requestCode, null, permissions);
            }
        }, PermissionConstant.STORAGE);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private void preDownload(String url, String contentDisposition, long contentLength) {
        File mFile = getFile(contentDisposition, url);
        if (mFile == null) {
            return;
        }
        if (mFile.exists() && mFile.length() >= contentLength) {
            Intent mIntent = AgentWebX5Utils.getCommonFileIntent(mContext, mFile);
            if (mIntent != null) {
                if (!(mContext instanceof Activity)) {
                    mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                mContext.startActivity(mIntent);
            }
            return;
        }
        if (ExecuteTasksMap.getInstance().contains(url)) {
            AgentWebX5Utils.toastShowShort(mContext, mDownLoadMsgConfig.getTaskHasBeenExist());
            return;
        }
        if (AgentWebX5Utils.checkNetworkType(mContext) > 1) { //移动数据
            showDialog(url, contentLength, mFile);
            return;
        }
        performDownload(url, contentLength, mFile);
    }

    private void performDownload(String url, long contentLength, File file) {
        ExecuteTasksMap.getInstance().addTask(url, file.getAbsolutePath());
        //并行下载.
        if (isParallelDownload.get()) {
            new RealDownLoader(new DownLoadTask(NotificationID++, url, this, isForce, enableIndicator, mContext, file, contentLength, mDownLoadMsgConfig, icon == -1 ? R.mipmap.download : icon)).executeOnExecutor(ExecutorProvider.getInstance().provide(), (Void[]) null);
        } else {
            //默认串行下载.
            new RealDownLoader(new DownLoadTask(NotificationID++, url, this, isForce, enableIndicator, mContext, file, contentLength, mDownLoadMsgConfig, icon == -1 ? R.mipmap.download : icon)).execute();
        }
    }

    private void showDialog(final String url, final long contentLength, final File file) {
        Activity mActivity;
        if ((mActivity = mActivityWeakReference.get()) == null || mActivity.isFinishing()) {
            return;
        }
        AlertDialog mAlertDialog;
        mAlertDialog = new AlertDialog.Builder(mActivity)
                .setTitle(mDownLoadMsgConfig.getTips())
                .setMessage(mDownLoadMsgConfig.getHoneycomblow())
                .setNegativeButton(mDownLoadMsgConfig.getDownLoad(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        isForce = true;
                        performDownload(url, contentLength, file);
                    }
                })
                .setPositiveButton(mDownLoadMsgConfig.getCancel(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                }).create();
        mAlertDialog.show();
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private File getFile(String contentDisposition, String url) {
        String fileName = null;
        Matcher m = Pattern.compile(".*filename=(.*)").matcher(contentDisposition.toLowerCase());
        if (m.find()) {
            fileName = m.group(1);
        }
        if (TextUtils.isEmpty(fileName) && !TextUtils.isEmpty(url)) {
            Uri mUri = Uri.parse(url);
            fileName = Objects.requireNonNull(mUri.getPath()).substring(mUri.getPath().lastIndexOf('/') + 1);
        }
        if (!TextUtils.isEmpty(fileName) && fileName.length() > 64) {
            fileName = fileName.substring(fileName.length() - 64);
        }
        if (TextUtils.isEmpty(fileName)) {
            fileName = AgentWebX5Utils.md5(url);
        }
        try {
            return AgentWebX5Utils.createFileByName(mContext, fileName);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void success(String path) {
        ExecuteTasksMap.getInstance().removeTask(path);
        if (AgentWebX5Utils.isEmptyCollection(mDownLoadResultListeners)) {
            return;
        }
        for (DownLoadResultListener mDownLoadResultListener : mDownLoadResultListeners) {
            if (mDownLoadResultListener != null) {
                mDownLoadResultListener.success(path);
            }
        }
    }


    @Override
    public void error(String path, String resUrl, String cause, Throwable e) {
        ExecuteTasksMap.getInstance().removeTask(path);
        if (AgentWebX5Utils.isEmptyCollection(mDownLoadResultListeners)) {
            AgentWebX5Utils.toastShowShort(mContext, mDownLoadMsgConfig.getDownLoadFail());
            return;
        }
        for (DownLoadResultListener mDownLoadResultListener : mDownLoadResultListeners) {
            if (mDownLoadResultListener == null) {
                continue;
            }
            mDownLoadResultListener.error(path, resUrl, cause, e);
        }
    }

    static class ExecutorProvider implements IProvider<Executor> {
        private final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
        private final int CORE_POOL_SIZE = (int) (Math.max(2, Math.min(CPU_COUNT - 1, 4)) * 1.5);
        private final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;

        private final ThreadFactory sThreadFactory = new ThreadFactory() {
            private final AtomicInteger mCount = new AtomicInteger(1);
            private SecurityManager securityManager = System.getSecurityManager();
            private ThreadGroup group = securityManager != null ? securityManager.getThreadGroup() : Thread.currentThread().getThreadGroup();

            public Thread newThread(Runnable r) {
                Thread mThread = new Thread(group, r, "pool-agentweb-thread-" + mCount.getAndIncrement());
                if (mThread.isDaemon()) {
                    mThread.setDaemon(false);
                }
                mThread.setPriority(Thread.MIN_PRIORITY);
                LogUtils.getInstance().e(TAG, "Thread Name:" + mThread.getName());
                LogUtils.getInstance().e(TAG, "live:" + mThreadPoolExecutor.getActiveCount() + "    getCorePoolSize:" + mThreadPoolExecutor.getCorePoolSize() + "  getPoolSize:" + mThreadPoolExecutor.getPoolSize());
                return mThread;
            }
        };

        private static final BlockingQueue<Runnable> sPoolWorkQueue =
                new LinkedBlockingQueue<Runnable>(128);
        private ThreadPoolExecutor mThreadPoolExecutor;

        private ExecutorProvider() {
            internalInit();
        }

        private void internalInit() {
            if (mThreadPoolExecutor != null && !mThreadPoolExecutor.isShutdown()) {
                mThreadPoolExecutor.shutdownNow();
            }
            int KEEP_ALIVE_SECONDS = 15;
            mThreadPoolExecutor = new ThreadPoolExecutor(
                    CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    sPoolWorkQueue, sThreadFactory);
            mThreadPoolExecutor.allowCoreThreadTimeOut(true);
        }


        public static ExecutorProvider getInstance() {
            return InnerHolder.M_EXECUTOR_PROVIDER;
        }

        static class InnerHolder {
            private static final ExecutorProvider M_EXECUTOR_PROVIDER = new ExecutorProvider();
        }

        @Override
        public Executor provide() {
            return mThreadPoolExecutor;
        }

    }

    //静态缓存当前正在下载的任务url
    public static class ExecuteTasksMap extends ReentrantLock {

        private LinkedList mTasks;

        private ExecuteTasksMap() {
            super(false);
            mTasks = new LinkedList();
        }

        private static ExecuteTasksMap sInstance = null;

        static ExecuteTasksMap getInstance() {
            if (sInstance == null) {
                synchronized (ExecuteTasksMap.class) {
                    if (sInstance == null)
                        sInstance = new ExecuteTasksMap();
                }
            }
            return sInstance;
        }

        void removeTask(String path) {
            int index = mTasks.indexOf(path);
            if (index == -1)
                return;
            try {
                lock();
                int position;
                if ((position = mTasks.indexOf(path)) == -1)
                    return;
                mTasks.remove(position);
                mTasks.remove(position - 1);
            } finally {
                unlock();
            }
        }

        void addTask(String url, String path) {
            try {
                lock();
                mTasks.add(url);
                mTasks.add(path);
            } finally {
                unlock();
            }

        }

        //加锁读
        boolean contains(String url) {
            try {
                lock();
                return mTasks.contains(url);
            } finally {
                unlock();
            }
        }
    }

    public static class Builder {
        private Activity mActivity;
        private boolean isForce;
        private boolean enableIndicator;
        private List<DownLoadResultListener> mDownLoadResultListeners;
        private DownLoadMsgConfig mDownLoadMsgConfig;
        private IPermissionInterceptor mPermissionInterceptor;
        private int icon = -1;
        private boolean isParallelDownload = false;

        public Builder setActivity(Activity activity) {
            mActivity = activity;
            return this;
        }

        public Builder setForce(boolean force) {
            isForce = force;
            return this;
        }

        public Builder setEnableIndicator(boolean enableIndicator) {
            this.enableIndicator = enableIndicator;
            return this;
        }

        public Builder setDownLoadResultListeners(List<DownLoadResultListener> downLoadResultListeners) {
            this.mDownLoadResultListeners = downLoadResultListeners;
            return this;
        }

        public Builder setDownLoadMsgConfig(DownLoadMsgConfig downLoadMsgConfig) {
            mDownLoadMsgConfig = downLoadMsgConfig;
            return this;
        }

        public Builder setPermissionInterceptor(IPermissionInterceptor permissionInterceptor) {
            mPermissionInterceptor = permissionInterceptor;
            return this;
        }

        public Builder setIcon(int icon) {
            this.icon = icon;
            return this;
        }

        public Builder setParallelDownload(boolean parallelDownload) {
            isParallelDownload = parallelDownload;
            return this;
        }

        public DefaultDownLoaderImpl create() {
            return new DefaultDownLoaderImpl(this);
        }
    }
}

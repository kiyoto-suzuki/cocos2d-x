package org.cocos2dx.lib.experimental;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.FileAsyncHttpResponseHandler;
import com.loopj.android.http.RequestHandle;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.message.BasicHeader;
import cz.msebera.android.httpclient.client.HttpResponseException;

import org.cocos2dx.lib.Cocos2dxHelper;

import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import android.util.Log;
import android.util.SparseArray;

class TaskHandler extends FileAsyncHttpResponseHandler {
    int mId;
    File mFinalFile;

    private long mInitFileLen;
    private long mLastBytesWritten;
    private Cocos2dxDownloader mDownloader;

    public TaskHandler(Cocos2dxDownloader downloader, int id, File temp, File finalFile) {
        super(temp, true);
        mFinalFile = finalFile;
        mDownloader = downloader;
        mId = id;
        mInitFileLen = getTargetFile().length();
        mLastBytesWritten = 0;
    }
    
    private void LogD(String msg) {
    	Log.d("Cocos2dxDownloader", msg);
    }

    @Override
    public void onProgress(long bytesWritten, long totalSize) {
        //LogD("onProgress(bytesWritten:" + bytesWritten + " totalSize:" + totalSize);
        long dlBytes = bytesWritten - mLastBytesWritten;
        long dlNow = bytesWritten + mInitFileLen;
        long dlTotal = totalSize + mInitFileLen;
        mDownloader.onProgress(mId, dlBytes, dlNow, dlTotal);
        mLastBytesWritten = bytesWritten;
    }

    @Override
    public void onStart() {
        mDownloader.onStart(mId);
    }

    @Override
    public void onFinish() {
        // onFinish called after onSuccess/onFailure
        mDownloader.runNextTaskIfExists();
    }

    @Override
    public void onFailure(int i, Header[] headers, Throwable throwable, File file) {
        //LogD("onFailure(i:" + i + " headers:" + headers + " throwable:" + throwable + " file:" + file);
    	if (throwable instanceof HttpResponseException) {
    		HttpResponseException e = (HttpResponseException) throwable;
    		int statusCode = e.getStatusCode();
    		// Requested Range Not Satisfiable, delete the temp file to keep it from happening again
    		if (statusCode == 416) {
    			file.delete();
    		}
    	}
        
    	String errStr = "";
        if (null != throwable) {
            errStr = throwable.toString();
        }
        mDownloader.onFinish(mId, i, errStr);
    }

    @Override
    public void onSuccess(int i, Header[] headers, File file) {
        //LogD("onSuccess(i:" + i + " headers:" + headers + " file:" + file);
        String errStr = null;
        do {
            // rename temp file to final file
            // if final file exist, remove it
            if (mFinalFile.exists()) {
                if (mFinalFile.isDirectory()) {
                    errStr = "Dest file is directory:" + mFinalFile.getAbsolutePath();
                    break;
                }
                if (false == mFinalFile.delete()) {
                    errStr = "Can't remove old file:" + mFinalFile.getAbsolutePath();
                    break;
                }
            }

            File tempFile = getTargetFile();
            tempFile.renameTo(mFinalFile);
        } while (false);
        mDownloader.onFinish(mId, 0, errStr);
    }
}

class DownloadTask {

    DownloadTask(int id, String url, String path) {
    	mIsStarted = false;
        mHandle = null;
        mHandler = null;
        mId = id;
        mUrl = url;
        mPath = path;
        resetStatus();
    }

    void resetStatus() {
        mBytesReceived = 0;
        mTotalBytesReceived = 0;
        mTotalBytesExpected = 0;
    }

    RequestHandle mHandle;
    TaskHandler mHandler;
    boolean mIsStarted;

    // progress
    long mBytesReceived;
    long mTotalBytesReceived;
    long mTotalBytesExpected;
    
    // task info for resuming
    int mId;
    String mUrl;
    String mPath;
}

public class Cocos2dxDownloader {
	private static final int DEFAULT_MAX_CONNECTIONS = 6;
	
    private int mId;
    private AsyncHttpClient mHttpClient = new AsyncHttpClient();
    private String mTempfileSuffix;
    private int mMaxConnections = DEFAULT_MAX_CONNECTIONS;
    private SparseArray<DownloadTask> mRunningTasks = new SparseArray<DownloadTask>();
    private Queue<Runnable> mTaskQueue = new LinkedList<Runnable>();
    private boolean mIsSuspended = false;

    public int getMaxConnections() {
		return mMaxConnections;
	}

	public void setMaxConnections(int maxConnections) {
		this.mMaxConnections = maxConnections;
	}

	void onProgress(final int taskId, final long downloadBytes, final long downloadNow, final long downloadTotal) {
        DownloadTask task = (DownloadTask)mRunningTasks.get(taskId);
        if (null != task) {
            task.mBytesReceived = downloadBytes;
            task.mTotalBytesReceived = downloadNow;
            task.mTotalBytesExpected = downloadTotal;
        }
        nativeOnProgress(mId, taskId, downloadBytes, downloadNow, downloadTotal);
    }

    void onStart(int taskId) {
        DownloadTask task = mRunningTasks.get(taskId);
        if (null != task) {
            task.resetStatus();
        }
    }

    public void onFinish(final int taskId, final int errCode, final String errStr) {
        DownloadTask task = mRunningTasks.get(taskId);
        if (null == task) return;
        mRunningTasks.remove(taskId);
        nativeOnFinish(mId, taskId, errCode, errStr);
    }

    public Cocos2dxDownloader(int id, int timeoutInSeconds, String tempFileNameSufix) {
        mId = id;

        mHttpClient.setEnableRedirects(true);
        if (timeoutInSeconds > 0) {
            mHttpClient.setTimeout(timeoutInSeconds * 1000);
        }
        // downloader.mHttpClient.setMaxRetriesAndTimeout(3, timeoutInSeconds * 1000);
        AsyncHttpClient.allowRetryExceptionClass(javax.net.ssl.SSLException.class);

        mTempfileSuffix = tempFileNameSufix;
    }
    
    public boolean isSuspended() {
        return mIsSuspended;
    }
    
    public void startTask(DownloadTask task) {
    	if (task.mIsStarted) {
    		return;
    	}
    	task.mIsStarted = true;
    	final DownloadTask finalTask = task;
		Runnable startTaskInternalRunnable = new Runnable() {
			@Override
			public void run() {
    	        try {
    	        	startTaskInternal(finalTask);
    	        } catch (IllegalArgumentException e) {
    	            String errStr = "Can't start DownloadTask for " + finalTask.mUrl;
    	            nativeOnFinish(mId, finalTask.mId, 0, errStr);
    	        }
			}
		};
		enqueueTask(startTaskInternalRunnable);
    }
    
    // for internally starting a task
    private void startTaskInternal(DownloadTask task) throws IllegalArgumentException {
        File tempFile = new File(task.mPath + mTempfileSuffix);
        if (tempFile.isDirectory()) throw new IllegalArgumentException();

        File parent = tempFile.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalArgumentException();

        File finalFile = new File(task.mPath);
        if (finalFile.isDirectory()) throw new IllegalArgumentException();

        task.mHandler = new TaskHandler(this, task.mId, tempFile, finalFile);
        
        // do not start the request if downloader is currently suspended
        if (!mIsSuspended) {
            Header[] headers = null;
            long fileLen = tempFile.length();
            if (fileLen > 0) {
                // continue download
                List<Header> list = new ArrayList<Header>();
                list.add(new BasicHeader("Range", "bytes=" + fileLen + "-"));
                headers = list.toArray(new Header[list.size()]);
            }
            task.mHandle = mHttpClient.get(Cocos2dxHelper.getActivity(), task.mUrl, headers, null, task.mHandler);
        }
        mRunningTasks.put(task.mId, task);
    }
    
    public void cancelAllRequests() {
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable cancel = new Runnable() {
            @Override
            public void run() {
                synchronized (mTaskQueue) {
                    for (int i = 0; i < mRunningTasks.size(); i++) {
                        DownloadTask task = mRunningTasks.valueAt(i);
                        if (null != task.mHandle) {
                            task.mHandle.cancel(true);
                        }
                    }
                }
                latch.countDown();
            }
        };
        Cocos2dxHelper.getActivity().runOnUiThread(cancel);
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void suspend() {
        Cocos2dxHelper.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (mTaskQueue) {
                    mIsSuspended = true;
                    for (int i = 0; i < mRunningTasks.size(); i++) {
                        DownloadTask task = mRunningTasks.valueAt(i);
                        if (null != task.mHandle) {
                            task.mHandle.cancel(true);
                        }
                    }
                }
            }
        });
    }

    public void resume() {
        Cocos2dxHelper.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (mTaskQueue) {
                    mIsSuspended = false;
                    for (int i = 0; i < mRunningTasks.size(); i++) {
                        DownloadTask task = mRunningTasks.valueAt(i);
                        if (task.mHandle == null || task.mHandle.isCancelled()) {
                            task.resetStatus();
                            startTaskInternal(task);
                        }
                    }
                }
            }
        });
    }

    private void enqueueTask(Runnable taskRunnable) {
        synchronized (mTaskQueue) {
            if (mRunningTasks.size() < mMaxConnections) {
                Cocos2dxHelper.getActivity().runOnUiThread(taskRunnable);
            } else {
                mTaskQueue.add(taskRunnable);
            }
        }
    }

    public void runNextTaskIfExists() {
        synchronized (mTaskQueue) {
            Runnable startTaskRunnable = Cocos2dxDownloader.this.mTaskQueue.poll();
            if (startTaskRunnable != null) {
                Cocos2dxHelper.getActivity().runOnUiThread(startTaskRunnable);
            }
        }
    }

    native void nativeOnProgress(int id, int taskId, long bytesWritten, long totalBytesWritten, long totalBytesExpectedToWrite);
    native void nativeOnFinish(int id, int taskId, int errCode, String errStr);
}

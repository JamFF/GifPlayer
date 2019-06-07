package com.ff.gifplayer;

import android.Manifest;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity implements View.OnClickListener,
        EasyPermissions.PermissionCallbacks {

    private static final String TAG = "GifPlayer";
    private static final String TAG_PERMISSION = "Permission";
    private static final int PERMISSION_STORAGE_CODE_JAVA = 10001;
    private static final int PERMISSION_STORAGE_CODE_C = 10002;
    private static final String PERMISSION_STORAGE_MSG = "需要SD卡读取权限，否则无法正常使用";
    private static final String[] PERMS = {Manifest.permission.READ_EXTERNAL_STORAGE};
    private File file;
    private ImageView mImageView;
    private Bitmap mBitmap;
    private GifHandler mGifHandler;
    private volatile boolean isStopNDK = false;// 停止播放
    private ProgressDialog dialog;// 加载框

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        initData();
    }

    private void initView() {
        mImageView = findViewById(R.id.iv);
        findViewById(R.id.bt_c_load).setOnClickListener(this);
        findViewById(R.id.bt_java_load).setOnClickListener(this);
    }

    private void initData() {
        file = new File(Environment.getExternalStorageDirectory(), "demo.gif");
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.bt_java_load:
                gifForJava();
                break;
            case R.id.bt_c_load:
                gifForNDK();
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        switch (requestCode) {
            case PERMISSION_STORAGE_CODE_JAVA:
                Log.d(TAG_PERMISSION, "onPermissionsGranted: Java");
                break;
            case PERMISSION_STORAGE_CODE_C:
                Log.d(TAG_PERMISSION, "onPermissionsGranted: C");
                break;
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            // 拒绝权限，并不再询问
            new AppSettingsDialog
                    .Builder(this)
                    .setTitle("授权提醒")
                    .setRationale(PERMISSION_STORAGE_MSG)
                    .setPositiveButton("打开设置")
                    .setNegativeButton("取消")
                    .build()
                    .show();
        } else {
            // 拒绝权限
            switch (requestCode) {
                case PERMISSION_STORAGE_CODE_JAVA:
                    Log.d(TAG_PERMISSION, "onPermissionsDenied: Java");
                    break;
                case PERMISSION_STORAGE_CODE_C:
                    Log.d(TAG_PERMISSION, "onPermissionsDenied: C");
                    break;
            }
        }
    }

    /**
     * Glide方式加载GIF图
     */
    @AfterPermissionGranted(PERMISSION_STORAGE_CODE_JAVA)
    private void gifForJava() {
        if (!EasyPermissions.hasPermissions(this, PERMS)) {
            // 申请权限
            EasyPermissions.requestPermissions(this, PERMISSION_STORAGE_MSG,
                    PERMISSION_STORAGE_CODE_JAVA, PERMS);
            return;
        }
        isStopNDK = true;
        Glide.with(this).load(file).listener(new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                        Target<Drawable> target, boolean isFirstResource) {
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                           DataSource dataSource, boolean isFirstResource) {
                /*if (resource instanceof GifDrawable) {
                    // 设置循环次数，默认无限循环
                    ((GifDrawable) resource).setLoopCount(1);
                }*/
                return false;
            }
        }).into(mImageView);
    }

    /**
     * NDK方式加载GIF图，占用内存比Glide大，播放要慢，说明Glide进行了深度优化，正常情况C要更快更节约内存
     */
    @AfterPermissionGranted(PERMISSION_STORAGE_CODE_C)
    private void gifForNDK() {
        if (!EasyPermissions.hasPermissions(this, PERMS)) {
            // 申请权限
            EasyPermissions.requestPermissions(this, PERMISSION_STORAGE_MSG,
                    PERMISSION_STORAGE_CODE_C, PERMS);
            return;
        }
        new GifTask(this).execute();
    }

    private void showProgressDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (dialog == null) {
                    dialog = new ProgressDialog(MainActivity.this);
                    dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                }
                dialog.setMessage("加载中");
                dialog.setCancelable(false);// 点击屏幕和按返回键都不能取消加载框
                dialog.show();
            }
        });
    }

    private void dismissProgressDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (dialog != null) {
                    dialog.dismiss();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        isStopNDK = true;
        dismissProgressDialog();
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }
        super.onDestroy();
    }

    private static class GifTask extends AsyncTask<Void, Float, String> {

        private WeakReference<MainActivity> mReference;

        private GifTask(MainActivity activity) {
            mReference = new WeakReference<>(activity);
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "准备播放");
            if (mReference != null && mReference.get() != null) {
                mReference.get().showProgressDialog();
            }
        }

        @Override
        protected String doInBackground(Void... voids) {
            if (mReference != null && mReference.get() != null) {
                MainActivity activity = mReference.get();
                // FIXME: 2019-06-06 C代码中存在内存泄漏，尚未完善
                activity.mGifHandler = new GifHandler(activity.file.getAbsolutePath());
                int width = activity.mGifHandler.getWidth();
                int height = activity.mGifHandler.getHeight();
                activity.mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                mReference.get().dismissProgressDialog();

                int nextFrame;// 绘制下一帧的时间间隔
                while (!activity.isStopNDK) {
                    nextFrame = activity.mGifHandler.updateFrame(activity.mBitmap);
                    publishProgress(activity.mGifHandler.getProgress());
                    try {
                        Thread.sleep(nextFrame);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            return "停止播放";
        }

        @Override
        protected void onProgressUpdate(Float... values) {
            Log.d(TAG, "onProgressUpdate: " + values[0] + "%");
            if (mReference != null && mReference.get() != null) {
                MainActivity activity = mReference.get();
                activity.mImageView.setImageBitmap(activity.mBitmap);
            }
        }

        @Override
        protected void onPostExecute(String result) {
            Log.d(TAG, result);
        }

        @Override
        protected void onCancelled() {
            Log.d(TAG, "取消播放");
        }
    }
}
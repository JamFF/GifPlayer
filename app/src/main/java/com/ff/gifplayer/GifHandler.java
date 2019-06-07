package com.ff.gifplayer;

import android.graphics.Bitmap;

/**
 * description:
 * author: FF
 * time: 2019-06-05 16:39
 */
public class GifHandler {

    private long gifAddress;// 结构体的地址

    static {
        System.loadLibrary("native-lib");
    }

    public GifHandler(String path) {
        gifAddress = loadPath(path);
    }

    public int getWidth() {
        return getWidth(gifAddress);
    }

    public int getHeight() {
        return getHeight(gifAddress);
    }

    public float getProgress() {
        return getProgress(gifAddress);
    }

    /**
     * 获取下一帧的刷新事件
     */
    public int updateFrame(Bitmap bitmap) {
        return updateFrame(gifAddress, bitmap);
    }

    private native long loadPath(String path);// 返回值就是gifAddress，也是下面参数中的ndkGif

    private native int getWidth(long ndkGif);

    private native int getHeight(long ndkGif);

    private native float getProgress(long ndkGif);

    // 每帧的调用事件，隔一段事件调用一次
    private native int updateFrame(long ndkGif, Bitmap bitmap);
}

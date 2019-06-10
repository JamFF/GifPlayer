#include <jni.h>
#include <string>
#include <malloc.h>
#include <cstring>
#include <android/bitmap.h>
#include <android/log.h>
#include "gif_lib.h"

#define  LOG_TAG "GifPlayer"
// 方法别名
#define  LOG_D(...)  __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)
#define  argb(a, r, g, b) ( ((a) & 0xff) << 24 ) | ( ((b) & 0xff) << 16 ) | ( ((g) & 0xff) << 8 ) | ((r) & 0xff)

/***
 * 自定义结构体，用来保存帧信息
 */
typedef struct GifBean {
    int current_frame;// 播放当前帧，从0帧开始
    int total_frame;// 总帧数
    int *delays;// 延迟时间数组
} GifBean;

extern "C"
JNIEXPORT jlong JNICALL
Java_com_ff_gifplayer_GifHandler_loadPath(JNIEnv *env, jobject instance, jstring path_) {

    const char *path = env->GetStringUTFChars(path_, 0);

    int err;
    // 用系统函数打开一个gif文件，返回一个结构体，这个结构体为句柄
    GifFileType *gifFileType = DGifOpenFileName(path, &err);
    DGifSlurp(gifFileType);// 对gifFileType结构体，初始化内存

    // 初始化GifBean
    auto *gifBean = static_cast<GifBean *>(malloc(sizeof(GifBean)));
    memset(gifBean, 0, sizeof(GifBean));// 清空内存空间，常用于内存空间初始化

    // 初始化延迟时间数组，delays内存大小为：总帧数乘以int的大小
    gifBean->delays = static_cast<int *>(malloc(sizeof(int) * gifFileType->ImageCount));
    memset(gifBean->delays, 0, sizeof(int) * gifFileType->ImageCount);

    gifBean->current_frame = 0;
    gifBean->total_frame = gifFileType->ImageCount;

    ExtensionBlock *ext;
    SavedImage frame;
    LOG_D("总帧数 %d", gifFileType->ImageCount);
    for (int i = 0; i < gifFileType->ImageCount; ++i) {
        frame = gifFileType->SavedImages[i];// 得到每一帧
        for (int j = 0; j < frame.ExtensionBlockCount; ++j) {
            // 找到图形控制扩展块
            if (frame.ExtensionBlocks[j].Function == GRAPHICS_EXT_FUNC_CODE) {
                ext = &frame.ExtensionBlocks[j];
                if (ext) {
                    // Bytes中第二位是低8位，第三位是高8位
                    int delay = ext->Bytes[2] << 8 | ext->Bytes[1];
                    // 延迟时间 单位1/100秒，如果值不为1，表示暂停规定时间后再继续往下处理数据流
                    gifBean->delays[i] = 10 * delay;// 乘以10，换算成毫秒值
                    LOG_D("延迟时间 %d", gifBean->delays[i]);
                }
                break;
            }
        }
    }

    // 将GifBean绑定给gifFileType的UserData，类似于View.setTag();
    gifFileType->UserData = gifBean;

    env->ReleaseStringUTFChars(path_, path);

    return reinterpret_cast<jlong>(gifFileType);// 将地址强转为java的long类型
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ff_gifplayer_GifHandler_getWidth(JNIEnv *env, jobject instance, jlong ndkGif) {

    auto *gifFileType = reinterpret_cast<GifFileType *>(ndkGif);
    return gifFileType->SWidth;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ff_gifplayer_GifHandler_getHeight(JNIEnv *env, jobject instance, jlong ndkGif) {

    auto *gifFileType = reinterpret_cast<GifFileType *>(ndkGif);
    return gifFileType->SHeight;
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_ff_gifplayer_GifHandler_getProgress(JNIEnv *env, jobject instance, jlong ndkGif) {

    auto *gifFileType = reinterpret_cast<GifFileType *>(ndkGif);
    auto *gifBean = static_cast<GifBean *>(gifFileType->UserData);
    return (gifBean->current_frame + 1) * 100.0F / gifBean->total_frame;
}

/**
 * 绘制一张图片
 * @param gifFileType   保存gif图片信息的结构体
 * @param gifBean       保存gif图片帧信息的结构体
 * @param info          用来渲染gif图片的bitmap的信息
 * @param pixels        需要渲染的gif图片的首地址
 */
void drawFrame(GifFileType *gifFileType, GifBean *gifBean, AndroidBitmapInfo info, void *pixels) {

    // 获取当前帧的数据源
    SavedImage savedImage = gifFileType->SavedImages[gifBean->current_frame];
    // 获取图形说明
    GifImageDesc frameInfo = savedImage.ImageDesc;

    int pointPixel;// 图形中一个像素的索引值，不是指针，在颜色表中的索引
    GifByteType gifByteType;// 压缩数据
    GifColorType gifColorType;// 解压数据，里面存储着RGB
    // 当前帧的字典，RGB数据，压缩工具
    ColorMapObject *colorMapObject = frameInfo.ColorMap;
    if (colorMapObject == nullptr) {
        // 有些gif的GifImageDesc中没有ColorMap，那么就要从GifFileType里面取
        colorMapObject = gifFileType->SColorMap;
    }

    // 1. 待渲染的图片首地址，先向下偏移
    // stride是字节数，pixels需要强转为char指针才可以一字节一字节的相加
    // 如果使用int指针，4个字节，如果加的不是4的整数倍，会出异常
    int *px = (int *) ((char *) pixels + info.stride * frameInfo.Top);

    // 绘制的区域并不是0,0坐标开始，要取gif的边界区域
    for (int y = frameInfo.Top; y < frameInfo.Top + frameInfo.Height; ++y) {

        // 2. 在 x = frameInfo.Left 这里向右偏移
        for (int x = frameInfo.Left; x < frameInfo.Left + frameInfo.Width; ++x) {

            // 拿到每一个坐标位置的索引值，索引值从0开始，而坐标是有偏移的，所以计算公式如下
            pointPixel = (y - frameInfo.Top) * frameInfo.Width + (x - frameInfo.Left);

            // 根据索引值，获取压缩数据，RGB通过LZW算法压缩，缓存在一个字典中
            gifByteType = savedImage.RasterBits[pointPixel];

            // 解压数据，从字典中取出RGB
            gifColorType = colorMapObject->Colors[gifByteType];

            // 3. 渲染当前行的每一个坐标的颜色（该坐标是已经是向下、向右偏移后的）
            // 注意GIF，首先，不能处理透明度，默认给255不透明；其次，是 B G R 的顺序
            px[x] = argb(255, gifColorType.Red, gifColorType.Green, gifColorType.Blue);
        }
        // 4. 切换到下一行首地址，与上面一样，需要先转为char指针再相加
        px = (int *) ((char *) px + info.stride);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ff_gifplayer_GifHandler_updateFrame(JNIEnv *env, jobject instance, jlong ndkGif,
                                             jobject bitmap) {

    // 强转代表gif图片的结构体
    auto *gifFileType = reinterpret_cast<GifFileType *>(ndkGif);
    auto *gifBean = static_cast<GifBean *>(gifFileType->UserData);

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);// 为bitmap创建信息

    // 代表一幅图片的像素数组，也是整幅图片的首地址
    void *pixels;

    // 锁定bitmap，一幅图片-->二维数组
    AndroidBitmap_lockPixels(env, bitmap, &pixels);// 最后一个参数需要二级指针

    drawFrame(gifFileType, gifBean, info, pixels);// 绘制帧
    gifBean->current_frame += 1;// 下一帧
    if (gifBean->current_frame >= gifBean->total_frame) {
        gifBean->current_frame = 0;
        LOG_D("重新过来，下一帧 %d", gifBean->current_frame);
    } else {
        LOG_D("下一帧 %d", gifBean->current_frame);
    }

    // 解锁bitmap
    AndroidBitmap_unlockPixels(env, bitmap);

    // 返回下一帧的延迟时间
    return gifBean->delays[gifBean->current_frame];
}
#ifndef RDPSYNC_TEST_ANDROID_BITMAP_H
#define RDPSYNC_TEST_ANDROID_BITMAP_H
#include <stdint.h>
#include <jni.h>
#define ANDROID_BITMAP_RESULT_SUCCESS 0
#define ANDROID_BITMAP_FORMAT_RGBA_8888 1
typedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } AndroidBitmapInfo;
int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo*);
int AndroidBitmap_lockPixels(JNIEnv*, jobject, void**);
int AndroidBitmap_unlockPixels(JNIEnv*, jobject);
#endif

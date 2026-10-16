# Build file for tools/vdexExtractor (upstream source, unmodified).
# Same as upstream jni/Android.mk, plus -Wno-array-parameter: newer clang
# warns about a harmless array bound and upstream builds with -Werror.
LOCAL_PATH := $(call my-dir)/vdexExtractor/jni

include $(CLEAR_VARS)
LOCAL_MODULE    := vdexExtractor
SRC             := ../src
SRC_FILE_LIST   := $(sort $(wildcard $(LOCAL_PATH)/$(SRC)/*.c)) $(sort $(wildcard $(LOCAL_PATH)/$(SRC)/*/*.c))
LOCAL_SRC_FILES := $(SRC_FILE_LIST:$(LOCAL_PATH)/%=%)
LOCAL_CFLAGS    += -c -std=c11 -D_GNU_SOURCE -Wall -Wextra -Werror -Wno-array-parameter
LOCAL_LDFLAGS   += -lm -lz
LOCAL_CFLAGS    += -DVERSION=\"dev-78f283b\"
include $(BUILD_EXECUTABLE)

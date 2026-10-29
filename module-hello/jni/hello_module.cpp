// Hello World Zygisk module for the ZygiskRewire loader.
//
// Mirrors the rezygisk ABI (loader/src/injector/module.h):
//   struct rezygisk_abi { long api_version; void *impl;
//     pre_app_specialize; post_app_specialize;
//     pre_server_specialize; post_server_specialize; }
//   struct rezygisk_api { void *impl; bool (*register_module)(void*, void const*); }
// Entry: zygisk_module_entry(void *api, void *env) — dlsym'd by the loader's
// csoloader; the loader calls it once with (api-table, JNIEnv).
//
// Behavior: after post-app-specialize, poll for the app Application, load an
// embedded DEX (an ActivityLifecycleCallbacks that adds a "Hello World" banner
// to every resumed activity), and register it on the app.

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <cstring>
#include <cstdint>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "helloworld-zygisk", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "helloworld-zygisk", __VA_ARGS__)

// ---- embedded DEX (linked from dex_embed.o by the Makefile) ----
extern "C" {
extern const uint8_t hello_dex_start[];
extern const uint8_t hello_dex_end[];
}

namespace {

constexpr long kApiVersion = 5;      // REZYGISK_API_VERSION (loader rejects >5)
constexpr int kMaxTries = 50;        // 50 * 300ms = 15s app-ready poll budget
constexpr useconds_t kPollUs = 300000;
constexpr char kHookClass[] = "com.zygiskrewire.hello.HelloWorldHook";

struct Module {
  JavaVM *vm_cached = nullptr;

  JavaVM *vm() {
    if (!vm_cached) {
      auto get_vms = reinterpret_cast<jint (*)(JavaVM **, jsize, jsize *)>(
        dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
      JavaVM *vms[1] = {};
      jsize n = 0;
      if (get_vms && get_vms(vms, 1, &n) == JNI_OK && n == 1) {
        vm_cached = vms[0];
      }
    }
    return vm_cached;
  }

  void onPostApp() {
    pthread_t t;
    if (pthread_create(&t, nullptr, worker_entry, this) == 0) {
      pthread_detach(t);
    }
  }

  void worker() {
    JavaVM *jvm = vm();
    if (!jvm) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
      if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
      attached = true;
    }

    if (env->PushLocalFrame(64) != JNI_OK) {
      if (attached) jvm->DetachCurrentThread();
      return;
    }

    do {
      jclass activity_thread = env->FindClass("android/app/ActivityThread");
      if (!activity_thread) break;

      jmethodID current_app = env->GetStaticMethodID(
        activity_thread, "currentApplication", "()Landroid/app/Application;");
      if (!current_app) break;

      jobject app = nullptr;
      for (int i = 0; i < kMaxTries; ++i) {
        app = env->CallStaticObjectMethod(activity_thread, current_app);
        if (env->ExceptionCheck()) {
          env->ExceptionClear();
          app = nullptr;
        }
        if (app) break;
        usleep(kPollUs);
      }
      if (!app) {
        LOGE("app application never appeared");
        break;
      }

      jclass app_cls = env->GetObjectClass(app);
      jmethodID get_loader = env->GetMethodID(
        app_cls, "getClassLoader", "()Ljava/lang/ClassLoader;");
      jobject app_loader = env->CallObjectMethod(app, get_loader);
      if (env->ExceptionCheck() || !app_loader) {
        env->ExceptionClear();
        LOGE("no app classloader");
        break;
      }

      jclass byte_buffer = env->FindClass("java/nio/ByteBuffer");
      jmethodID wrap = byte_buffer
        ? env->GetStaticMethodID(byte_buffer, "wrap",
                                 "([B)Ljava/nio/ByteBuffer;")
        : nullptr;
      if (!byte_buffer || !wrap) {
        LOGE("ByteBuffer.wrap unavailable");
        break;
      }

      jsize dex_len = static_cast<jsize>(hello_dex_end - hello_dex_start);
      jbyteArray dex_arr = env->NewByteArray(dex_len);
      if (!dex_arr) break;
      env->SetByteArrayRegion(dex_arr, 0, dex_len,
                              reinterpret_cast<const jbyte *>(hello_dex_start));
      jobject dex_buf = env->CallStaticObjectMethod(byte_buffer, wrap, dex_arr);
      if (env->ExceptionCheck() || !dex_buf) {
        env->ExceptionClear();
        LOGE("wrap failed");
        break;
      }

      jclass imdl = env->FindClass("dalvik/system/InMemoryDexClassLoader");
      jmethodID imdl_ctor = imdl
        ? env->GetMethodID(imdl, "<init>",
                           "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V")
        : nullptr;
      if (!imdl || !imdl_ctor) {
        LOGE("InMemoryDexClassLoader unavailable (API < 26?)");
        break;
      }

      jobject module_loader = env->NewObject(imdl, imdl_ctor, dex_buf, app_loader);
      if (env->ExceptionCheck() || !module_loader) {
        env->ExceptionClear();
        LOGE("dex loader creation failed");
        break;
      }

      jmethodID load_class = env->GetMethodID(
        imdl, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
      jclass hook_cls = static_cast<jclass>(env->CallObjectMethod(
        module_loader, load_class, env->NewStringUTF(kHookClass)));
      if (env->ExceptionCheck() || !hook_cls) {
        env->ExceptionClear();
        LOGE("hook class missing from embedded dex");
        break;
      }

      jmethodID attach = env->GetStaticMethodID(
        hook_cls, "attach", "(Landroid/app/Application;)V");
      if (!attach) {
        LOGE("attach method missing");
        break;
      }

      LOGI("registering hello hook on the app");
      env->CallStaticVoidMethod(hook_cls, attach, app);
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("attach threw");
      }
    } while (false);

    env->PopLocalFrame(nullptr);
    if (attached) jvm->DetachCurrentThread();
    LOGI("worker done");
  }

  static void *worker_entry(void *arg) {
    static_cast<Module *>(arg)->worker();
    return nullptr;
  }
};

Module g_module;

struct Abi {
  long api_version;
  void *impl;
  void (*pre_app_specialize)(void *, void *);
  void (*post_app_specialize)(void *, const void *);
  void (*pre_server_specialize)(void *, void *);
  void (*post_server_specialize)(void *, const void *);
};

struct Api {
  void *impl;
  bool (*register_module)(void *, void const *);
  // Remaining members unused by this module; layout matches the loader.
};

Abi g_abi = {
  kApiVersion,
  nullptr, // impl filled at entry
  nullptr, // pre_app_specialize — loader skips null callbacks
  [](void *self, const void *) { if (self) static_cast<Module *>(self)->onPostApp(); },
  nullptr,
  nullptr,
};

} // namespace

// Entry called by the ZygiskRewire loader once, at injection time in the
// zygote. Registers the ABI; the per-app worker is spawned by the loader's
// post_app_specialize callback in each forked app process (never here — the
// zygote has no Application, and JNI work at injection time is unsafe).
extern "C" __attribute__((visibility("default"))) void
zygisk_module_entry(void *api, void *env) {
  g_abi.impl = &g_module;
  static_cast<Api *>(api)->register_module(api, &g_abi);
  (void)env;
  (void)env;
}

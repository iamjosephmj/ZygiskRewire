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
#include <cstdio>
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
constexpr uint32_t kUserAppMinUid = 10000; // user-installed apps start at 10000

// Detector apps the banner targets; everything else stays unhooked.
constexpr char kTargetPkgA[] = "tech.thessemaj.deviceintelligence.sample";
constexpr char kTargetPkgB[] = "at.persie0.root_detection_app";

struct Module {
  JavaVM *vm_cached = nullptr;
  JNIEnv *env_cached = nullptr; // captured at entry; valid in forked app procs
  bool should_hook = false;     // set in pre_app_specialize: uid >= 10000 AND package match
  char matched_pkg[128] = {};   // package passed to the Java attach, valid when should_hook

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
    if (!should_hook) return; // not a target detector app: no banner
    pthread_t t;
    if (pthread_create(&t, nullptr, worker_entry, this) == 0) {
      pthread_detach(t);
    }
  }

  // args mirrors the first fields of the loader's app_specialize_args_v5
  // (loader/src/injector/module.h): uid, gid, gids, runtime_flags, rlimits,
  // mount_external, se_info, nice_name. Only uid and nice_name are read.
  void onPreApp(void *args) {
    struct ArgsPrefix {
      jint *uid;
      jint *gid;
      jintArray *gids;
      jint *runtime_flags;
      jobjectArray *rlimits;
      jint *mount_external;
      jstring *se_info;
      jstring *nice_name;
    };

    should_hook = false;
    matched_pkg[0] = '\0';
    if (!args) return;
    const auto *a = static_cast<const ArgsPrefix *>(args);
    uint32_t uid = a->uid ? static_cast<uint32_t>(*a->uid) : 0;
    if (uid < kUserAppMinUid || !a->nice_name || !*a->nice_name) return;

    JNIEnv *env = env_cached;
    if (!env) return;
    const char *name = env->GetStringUTFChars(*a->nice_name, nullptr);
    if (!name) return;
    bool matched = strcmp(name, kTargetPkgA) == 0 || strcmp(name, kTargetPkgB) == 0;
    if (matched) {
      snprintf(matched_pkg, sizeof(matched_pkg), "%s", name);
      should_hook = true;
      LOGI("target matched: uid=%u pkg=%s", uid, name);
    } else {
      LOGI("not a target: uid=%u pkg=%s", uid, name);
    }
    env->ReleaseStringUTFChars(*a->nice_name, name);
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

      // Preferred: attach(Application, String) so the banner can show which
      // detector was bypassed; fall back to the original one-arg signature.
      jmethodID attach = env->GetStaticMethodID(
        hook_cls, "attach",
        "(Landroid/app/Application;Ljava/lang/String;)V");
      if (attach) {
        jstring pkg = env->NewStringUTF(matched_pkg[0] ? matched_pkg : "unknown");
        if (env->ExceptionCheck()) {
          env->ExceptionClear();
          pkg = nullptr;
          attach = nullptr;
        }
        if (attach) {
          LOGI("registering hello hook on the app (pkg=%s)",
               matched_pkg[0] ? matched_pkg : "unknown");
          env->CallStaticVoidMethod(hook_cls, attach, app, pkg);
        }
      } else {
        attach = env->GetStaticMethodID(
          hook_cls, "attach", "(Landroid/app/Application;)V");
        if (!attach) {
          LOGE("attach method missing");
          break;
        }
        LOGI("registering hello hook on the app (legacy attach)");
        env->CallStaticVoidMethod(hook_cls, attach, app);
      }
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("attach threw");
      }

      // Retro-show: registration can land after the launch activity's
      // onResume, so poll briefly for an already-focused activity and show
      // the banner on it directly.
      jmethodID show_now = env->GetStaticMethodID(
        hook_cls, "showNow", "(Landroid/app/Activity;Ljava/lang/String;)V");
      if (!show_now) {
        env->ExceptionClear();
      } else {
        for (int i = 0; i < 10; ++i) {
          jobject focused = find_focused_activity(env);
          if (focused) {
            jstring pkg = env->NewStringUTF(matched_pkg[0] ? matched_pkg : "unknown");
            env->CallStaticVoidMethod(hook_cls, show_now, focused, pkg);
            if (env->ExceptionCheck()) {
              env->ExceptionDescribe(); // prints the Java stack trace
              env->ExceptionClear();
              LOGE("showNow threw");
            }
            env->DeleteLocalRef(focused);
            break;
          }
          usleep(kPollUs);
        }
      }
    } while (false);

    env->PopLocalFrame(nullptr);
    if (attached) jvm->DetachCurrentThread();
    LOGI("worker done");
  }

  // Finds the currently focused activity, or null. The callback registration
  // in worker() can land after the launch activity's onResume (the worker
  // polls for the Application), so the banner must be shown retroactively.
  // Walks ActivityThread.mActivities via JNI — native access to greylisted
  // platform fields is not subject to the hidden-API reflection ban.
  static jobject find_focused_activity(JNIEnv *env) {
    jclass at_cls = env->FindClass("android/app/ActivityThread");
    if (!at_cls) { env->ExceptionClear(); return nullptr; }
    jmethodID cur_at = env->GetStaticMethodID(
      at_cls, "currentActivityThread", "()Landroid/app/ActivityThread;");
    if (!cur_at) { env->ExceptionClear(); return nullptr; }
    jobject at = env->CallStaticObjectMethod(at_cls, cur_at);
    if (env->ExceptionCheck() || !at) { env->ExceptionClear(); return nullptr; }

    jfieldID acts_f = env->GetFieldID(
      at_cls, "mActivities", "Landroid/util/ArrayMap;");
    if (!acts_f) { env->ExceptionClear(); return nullptr; }
    jobject map = env->GetObjectField(at, acts_f);
    if (!map) return nullptr;

    jmethodID values = env->GetMethodID(
      env->GetObjectClass(map), "values", "()Ljava/util/Collection;");
    if (!values) { env->ExceptionClear(); return nullptr; }
    jobject coll = env->CallObjectMethod(map, values);
    if (env->ExceptionCheck() || !coll) { env->ExceptionClear(); return nullptr; }

    jmethodID iter_m = env->GetMethodID(
      env->GetObjectClass(coll), "iterator", "()Ljava/util/Iterator;");
    if (!iter_m) { env->ExceptionClear(); return nullptr; }
    jobject it = env->CallObjectMethod(coll, iter_m);
    if (env->ExceptionCheck() || !it) { env->ExceptionClear(); return nullptr; }

    jmethodID has_next = env->GetMethodID(
      env->GetObjectClass(it), "hasNext", "()Z");
    jmethodID next = env->GetMethodID(
      env->GetObjectClass(it), "next", "()Ljava/lang/Object;");
    jclass acr_cls = env->FindClass("android/app/ActivityThread$ActivityClientRecord");
    if (!has_next || !next || !acr_cls) {
      env->ExceptionClear();
      return nullptr;
    }
    jfieldID act_f = env->GetFieldID(acr_cls, "activity", "Landroid/app/Activity;");
    if (!act_f) { env->ExceptionClear(); return nullptr; }

    while (env->CallBooleanMethod(it, has_next)) {
      jobject rec = env->CallObjectMethod(it, next);
      if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
      jobject activity = rec ? env->GetObjectField(rec, act_f) : nullptr;
      env->DeleteLocalRef(rec);
      if (!activity) continue;
      jmethodID has_focus = env->GetMethodID(
        env->GetObjectClass(activity), "hasWindowFocus", "()Z");
      if (has_focus && env->CallBooleanMethod(activity, has_focus)) {
        return activity; // caller cleans up via PopLocalFrame
      }
      env->DeleteLocalRef(activity);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    return nullptr;
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
  [](void *self, void *args) {
    if (self) static_cast<Module *>(self)->onPreApp(args);
  },
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
  g_module.env_cached = static_cast<JNIEnv *>(env); // reused in pre_app_specialize
  static_cast<Api *>(api)->register_module(api, &g_abi);
}

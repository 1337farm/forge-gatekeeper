// llm_engine.cpp — bare-metal ONNX Runtime GenAI runtime (C API only).
//
// No llama.cpp, no MediaPipe, no ML Kit. Links against libonnxruntime.so and
// libonnxruntime-genai.so (staged by scripts/fetch-ort-android.sh).
//
// Threading: the GenAI C API is not thread safe per object. Each nativeInit
// creates an independent Engine (model+tokenizer); concurrent generates must
// use separate handles. A global mutex guards the handle registry only.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>

#include "ort_genai_c.h"

namespace {

constexpr int kDefaultMaxNewTokens = 512;

std::mutex g_registry_mutex;
std::unordered_map<int64_t, struct Engine*> g_engines;
int64_t g_next_handle = 1;
int g_live_count = 0;
bool g_telemetry_killed = false;

struct Engine {
  OgaConfig* config = nullptr;
  OgaModel* model = nullptr;
  OgaTokenizer* tokenizer = nullptr;
  std::string provider;  // effective EP, e.g. "XNNPACK" or "CPU"
};

// Throw a C++ exception carrying the Oga error text (if any).
[[noreturn]] void ThrowOga(const char* what, OgaResult* result) {
  std::string msg(what);
  if (result != nullptr) {
    const char* err = OgaResultGetError(result);
    if (err != nullptr && err[0] != '\0') {
      msg += ": ";
      msg += err;
    }
    OgaDestroyResult(result);
  }
  throw std::runtime_error(msg);
}

inline void CheckOga(const char* what, OgaResult* result) {
  if (result != nullptr) ThrowOga(what, result);
}

void ThrowJava(JNIEnv* env, const char* cls, const std::string& msg) {
  jclass c = env->FindClass(cls);
  if (c != nullptr) env->ThrowNew(c, msg.c_str());
}

// Minimal JSON string escaper for the chat-template messages payload.
std::string JsonEscape(const std::string& in) {
  std::string out;
  out.reserve(in.size() + 16);
  for (char ch : in) {
    switch (ch) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (static_cast<unsigned char>(ch) < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof(buf), "\\u%04x", ch);
          out += buf;
        } else {
          out += ch;
        }
    }
  }
  return out;
}

Engine* Lookup(int64_t handle) {
  std::lock_guard<std::mutex> lock(g_registry_mutex);
  auto it = g_engines.find(handle);
  if (it == g_engines.end()) throw std::runtime_error("unknown engine handle");
  return it->second;
}

}  // namespace

extern "C" {

// Create an engine for the GenAI model folder at model_dir.
// Uses XNNPACK when available (CPU path); falls back to the default CPU EP.
// Returns a native handle (>0), or throws RuntimeException on failure.
JNIEXPORT jlong JNICALL
Java_com_forgerig_gatekeeper_ort_LlmBridge_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                          jstring model_dir, jboolean use_xnnpack) {
  try {
    if (!g_telemetry_killed) {
      OgaSetTelemetryEnabled(false);  // privacy: no telemetry leaves the device
      g_telemetry_killed = true;
    }

    const char* dir = env->GetStringUTFChars(model_dir, nullptr);
    if (dir == nullptr) throw std::runtime_error("null model_dir");
    std::string path(dir);
    env->ReleaseStringUTFChars(model_dir, dir);

    auto* engine = new Engine();

    OgaConfig* config = nullptr;
    CheckOga("OgaCreateConfig", OgaCreateConfig(path.c_str(), &config));
    engine->config = config;

    bool xnnpack = false;
    if (use_xnnpack) {
      // Request XNNPACK explicitly. If the bundled ORT build lacks the EP,
      // the model creation below fails and we retry with default providers.
      if (OgaConfigClearProviders(config) == nullptr &&
          OgaConfigAppendProvider(config, "XNNPACK") == nullptr) {
        OgaModel* model = nullptr;
        OgaResult* r = OgaCreateModelFromConfig(config, &model);
        if (r == nullptr) {
          engine->model = model;
          engine->provider = "XNNPACK";
          xnnpack = true;
        } else {
          OgaDestroyResult(r);
        }
      }
    }
    if (!xnnpack) {
      // Default provider list (CPU EP). Covers builds without XNNPACK and
      // any EP whose native libs are absent on the device.
      OgaDestroyConfig(engine->config);
      engine->config = nullptr;
      OgaModel* model = nullptr;
      CheckOga("OgaCreateModel", OgaCreateModel(path.c_str(), &model));
      engine->model = model;
      engine->provider = "CPU";
    }

    OgaTokenizer* tokenizer = nullptr;
    CheckOga("OgaCreateTokenizer", OgaCreateTokenizer(engine->model, &tokenizer));
    engine->tokenizer = tokenizer;

    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const int64_t handle = g_next_handle++;
    g_engines[handle] = engine;
    g_live_count++;
    return handle;
  } catch (const std::exception& e) {
    ThrowJava(env, "java/lang/RuntimeException", std::string("llm_engine init: ") + e.what());
    return 0;
  }
}

// Reports the effective execution provider ("XNNPACK" or "CPU").
JNIEXPORT jstring JNICALL
Java_com_forgerig_gatekeeper_ort_LlmBridge_nativeGetProvider(JNIEnv* env, jobject /*thiz*/,
                                                                 jlong handle) {
  try {
    return env->NewStringUTF(Lookup(handle)->provider.c_str());
  } catch (const std::exception& e) {
    ThrowJava(env, "java/lang/RuntimeException", std::string("llm_engine provider: ") + e.what());
    return nullptr;
  }
}

// Generate up to max_new_tokens tokens for prompt. Each decoded token is
// delivered via listener.onToken(String) on the calling thread; the native
// side never holds global refs and deletes every per-token local ref.
// Returns the number of generated tokens.
JNIEXPORT jint JNICALL
Java_com_forgerig_gatekeeper_ort_LlmBridge_nativeGenerate(JNIEnv* env, jobject /*thiz*/,
                                                              jlong handle, jstring prompt,
                                                              jint max_new_tokens, jobject listener) {
  const char* prompt_chars = env->GetStringUTFChars(prompt, nullptr);
  if (prompt_chars == nullptr) {
    ThrowJava(env, "java/lang/RuntimeException", "llm_engine generate: null prompt");
    return 0;
  }
  std::string prompt_str(prompt_chars);
  env->ReleaseStringUTFChars(prompt, prompt_chars);

  jclass listener_cls = env->GetObjectClass(listener);
  jmethodID on_token =
      env->GetMethodID(listener_cls, "onToken", "(Ljava/lang/String;)V");
  if (on_token == nullptr) return 0;  // NoSuchMethodError already pending

  OgaSequences* sequences = nullptr;
  OgaGeneratorParams* params = nullptr;
  OgaGenerator* generator = nullptr;
  OgaTokenizerStream* stream = nullptr;

  auto cleanup = [&]() {
    if (stream != nullptr) OgaDestroyTokenizerStream(stream);
    if (generator != nullptr) OgaDestroyGenerator(generator);
    if (params != nullptr) OgaDestroyGeneratorParams(params);
    if (sequences != nullptr) OgaDestroySequences(sequences);
  };

  try {
    Engine* engine = Lookup(handle);

    // Prefer the model's own chat template (Llama/Qwen instruct formats);
    // fall back to the raw prompt when the model ships none.
    std::string messages =
        std::string("[{\"role\": \"user\", \"content\": \"") + JsonEscape(prompt_str) + "\"}]";
    const char* formatted = nullptr;
    if (OgaTokenizerApplyChatTemplate(engine->tokenizer, nullptr, messages.c_str(), nullptr,
                                      true, &formatted) == nullptr &&
        formatted != nullptr) {
      prompt_str.assign(formatted);
      OgaDestroyString(formatted);
    }

    CheckOga("OgaCreateSequences", OgaCreateSequences(&sequences));
    CheckOga("OgaTokenizerEncode",
             OgaTokenizerEncode(engine->tokenizer, prompt_str.c_str(), sequences));

    CheckOga("OgaCreateGeneratorParams",
             OgaCreateGeneratorParams(engine->model, &params));
    const int cap = max_new_tokens > 0 ? max_new_tokens : kDefaultMaxNewTokens;
    {
      // No model-side context_length getter exists here, so bound the
      // window conservatively: the fixed 8192 default above it died on
      // 4k-context models (Phi-3-mini: max_length 8192 > context_length
      // 4096). The generation loop below still enforces the caller's cap;
      // larger-window models simply run with a headroom-limited max_length.
      constexpr double kWindow = 4096.0;
      double total = static_cast<double>(cap) + 512.0;  // prompt headroom
      double length = total < 256.0 ? 256.0 : total;
      if (length > kWindow) length = kWindow;
      CheckOga("OgaGeneratorParamsSetSearchNumber",
               OgaGeneratorParamsSetSearchNumber(params, "max_length", length));
    }

    CheckOga("OgaCreateGenerator", OgaCreateGenerator(engine->model, params, &generator));
    CheckOga("OgaGenerator_AppendTokenSequences",
             OgaGenerator_AppendTokenSequences(generator, sequences));
    CheckOga("OgaCreateTokenizerStream",
             OgaCreateTokenizerStream(engine->tokenizer, &stream));

    int generated = 0;
    while (!OgaGenerator_IsDone(generator) && generated < cap) {
      CheckOga("OgaGenerator_GenerateNextToken", OgaGenerator_GenerateNextToken(generator));

      const int32_t* next = nullptr;
      size_t count = 0;
      CheckOga("OgaGenerator_GetNextTokens",
               OgaGenerator_GetNextTokens(generator, &next, &count));
      if (count == 0 || next == nullptr) break;

      const char* piece = nullptr;
      CheckOga("OgaTokenizerStreamDecode",
               OgaTokenizerStreamDecode(stream, next[0], &piece));
      if (piece != nullptr && piece[0] != '\0') {
        jstring jpiece = env->NewStringUTF(piece);
        env->CallVoidMethod(listener, on_token, jpiece);
        env->DeleteLocalRef(jpiece);
        if (env->ExceptionCheck()) break;  // Kotlin side failed: stop promptly
      }
      generated++;
    }

    cleanup();
    if (env->ExceptionCheck()) return generated;
    return generated;
  } catch (const std::exception& e) {
    cleanup();
    ThrowJava(env, "java/lang/RuntimeException", std::string("llm_engine generate: ") + e.what());
    return 0;
  }
}

// Destroys the engine. Calls OgaShutdown once the last engine is gone
// (the C API allows re-initialization afterwards).
JNIEXPORT void JNICALL
Java_com_forgerig_gatekeeper_ort_LlmBridge_nativeRelease(JNIEnv* env, jobject /*thiz*/,
                                                             jlong handle) {
  Engine* engine = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    auto it = g_engines.find(handle);
    if (it == g_engines.end()) return;
    engine = it->second;
    g_engines.erase(it);
    g_live_count--;
  }
  if (engine->tokenizer != nullptr) OgaDestroyTokenizer(engine->tokenizer);
  if (engine->model != nullptr) OgaDestroyModel(engine->model);
  if (engine->config != nullptr) OgaDestroyConfig(engine->config);
  delete engine;

  std::lock_guard<std::mutex> lock(g_registry_mutex);
  if (g_live_count == 0) OgaShutdown();
}

// ---- QNN NPU offload (Snapdragon Hexagon) ----
// Requires the QNN EP build of ORT plus the QNN HTP system libs present in
// /vendor/lib64 on the device. To enable, replace the XNNPACK block in
// nativeInit with:
//
//   OgaConfigClearProviders(config);
//   OgaConfigAppendProvider(config, "QNN");
//   OgaConfigSetProviderOption(config, "QNN", "backend_path", "libQnnHtp.so");
//   // Optional HTP perf profile, see QNN SDK docs:
//   // OgaConfigSetProviderOption(config, "QNN", "htp_performance_mode", "burst");
//   OgaModel* model = nullptr;
//   CheckOga("OgaCreateModelFromConfig(QNN)", OgaCreateModelFromConfig(config, &model));

}  // extern "C"

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include "llama.h"
#include <android/log.h>

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Handle holding model + context ───────────────────────────────────────────
struct ModelHandle {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    // Prompt tokens currently resident in the KV cache (positions [0, size)). Used to
    // reuse the unchanged leading prefix across generations: the persona + few-shot
    // block is constant per theme, so re-decoding it every reply wastes most of the
    // prompt-eval time (the dominant CPU cost). We diff the new prompt against this and
    // only decode the changed tail. Empty = nothing cached (next decode is a full one).
    std::vector<llama_token> cachedPrompt;
};

// ── Suppress verbose llama.cpp log output to Android logcat ──────────────────
static void android_log_callback(ggml_log_level level, const char* text, void* /*user_data*/) {
    // Only forward warnings and errors; skip INFO-level noise
    if (level == GGML_LOG_LEVEL_WARN)  LOGW("%s", text);
    else if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
}

// ── Safe UTF-8 → Java String ─────────────────────────────────────────────────
// env->NewStringUTF requires *modified* UTF-8 and aborts (under CheckJNI) on any
// 4-byte sequence (emoji / supplementary chars) or a sequence sliced mid-character
// when generation is cut at maxTokens. llama.cpp emits standard UTF-8, so route the
// bytes through java.lang.String(byte[], "UTF-8"): it decodes standard UTF-8 and
// replaces malformed trailing bytes with U+FFFD instead of crashing the process.
static jstring utf8ToJString(JNIEnv* env, const std::string& str) {
    jbyteArray bytes = env->NewByteArray((jsize)str.size());
    if (bytes == nullptr) return env->NewStringUTF("");
    env->SetByteArrayRegion(bytes, 0, (jsize)str.size(),
                            reinterpret_cast<const jbyte*>(str.data()));

    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor  = strClass ? env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V") : nullptr;
    if (strClass == nullptr || ctor == nullptr) {
        env->DeleteLocalRef(bytes);
        return env->NewStringUTF("");
    }

    jstring charset = env->NewStringUTF("UTF-8");
    jstring result  = (jstring) env->NewObject(strClass, ctor, bytes, charset);

    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    return result != nullptr ? result : env->NewStringUTF("");
}

extern "C" {

// ── nativeInit ────────────────────────────────────────────────────────────────
// Loads the GGUF model file and creates an inference context.
// Returns an opaque jlong handle (pointer to ModelHandle), or 0 on failure.
//
// NOTE: If this fails to compile with "llama_init_from_model: undeclared",
//       replace it with "llama_new_context_with_model" (renamed in b4927).
JNIEXPORT jlong JNICALL
Java_com_wsvdmeer_pwncompanion_ai_GgufInference_nativeInit(
        JNIEnv* env, jobject /*thiz*/,
        jstring jModelPath, jint nCtx, jint nThreads) {

    llama_log_set(android_log_callback, nullptr);

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Loading GGUF model: %s", modelPath);

    // Load model weights
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU-only — no GPU on most Android devices

    llama_model* model = llama_model_load_from_file(modelPath, mparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!model) {
        LOGE("llama_model_load_from_file failed");
        return 0L;
    }

    // Create context (KV cache, attention buffers, etc.)
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)nCtx;
    cparams.n_threads       = (int32_t)nThreads;
    cparams.n_threads_batch = (int32_t)nThreads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;  // safer default for mobile

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0L;
    }

    auto* handle = new ModelHandle{model, ctx};
    LOGI("Model ready, handle=%p", (void*)handle);
    return reinterpret_cast<jlong>(handle);
}

// ── Shared: feed a prompt into the KV cache, reusing the unchanged prefix ──────
// Tokenises `prompt`, diffs it against the tokens already resident in the KV cache
// (h->cachedPrompt), drops the changed tail and decodes only that. Returns false on
// failure (caller should bail). Used by both nativeGenerate and nativeGenerateStreaming
// so they share identical, tested KV-cache behaviour.
static bool feedPrompt(ModelHandle* h, const std::string& prompt) {
    const llama_vocab* vocab = llama_model_get_vocab(h->model);

    int nTokens = -llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                  nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (nTokens <= 0) { LOGE("Tokenise returned %d", nTokens); return false; }

    std::vector<llama_token> tokens(nTokens);
    int actual = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                tokens.data(), nTokens, /*add_special=*/true, /*parse_special=*/true);
    if (actual < 0) { LOGE("Tokenise (second pass) failed: %d", actual); return false; }
    tokens.resize(actual);

    llama_memory_t mem = llama_get_memory(h->ctx);
    int prefix = 0;
    {
        const int maxPrefix = std::min(tokens.size(), h->cachedPrompt.size());
        while (prefix < maxPrefix && tokens[prefix] == h->cachedPrompt[prefix]) prefix++;
        if (prefix >= (int)tokens.size()) prefix = (int)tokens.size() - 1;  // leave ≥1 to decode
        if (prefix < 0) prefix = 0;
    }

    if (prefix == 0) {
        llama_memory_clear(mem, /*data=*/true);
    } else if (!llama_memory_seq_rm(mem, /*seq_id=*/0, /*p0=*/prefix, /*p1=*/-1)) {
        LOGW("seq_rm unsupported — clearing and decoding full prompt");
        llama_memory_clear(mem, /*data=*/true);
        prefix = 0;
    }

    llama_batch batch = llama_batch_get_one(tokens.data() + prefix, (int32_t)tokens.size() - prefix);
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("llama_decode (prompt) failed");
        h->cachedPrompt.clear();
        llama_memory_clear(mem, /*data=*/true);
        return false;
    }
    h->cachedPrompt = tokens;
    if (prefix > 0) LOGI("Prefix-cache hit: reused %d / %zu prompt tokens", prefix, tokens.size());
    return true;
}

// Sampler chain: repeat-penalty (0.5B loops without it) → top_k → top_p → temp → dist.
static llama_sampler* buildSampler(float temperature) {
    llama_sampler* s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(s, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(s, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(s, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return s;
}

// ── nativeGenerate ────────────────────────────────────────────────────────────
// Synchronous full-response generation.  Returns the whole generated text.  Kept as
// the non-streaming fallback path.
JNIEXPORT jstring JNICALL
Java_com_wsvdmeer_pwncompanion_ai_GgufInference_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/,
        jlong jHandle, jstring jPrompt, jint maxTokens, jfloat temperature) {

    auto* h = reinterpret_cast<ModelHandle*>(jHandle);
    if (!h || !h->model || !h->ctx) {
        LOGE("nativeGenerate: null handle");
        return env->NewStringUTF("");
    }

    const char* promptStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptStr);
    env->ReleaseStringUTFChars(jPrompt, promptStr);

    if (!feedPrompt(h, prompt)) return env->NewStringUTF("");

    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    llama_sampler* sampler = buildSampler(temperature);

    std::string result;
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int n = llama_token_to_piece(vocab, tok, piece, (int)sizeof(piece) - 1, 0, true);
        if (n > 0) { piece[n] = '\0'; result += piece; }
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, next) != 0) {
            LOGW("llama_decode failed at token %d — stopping early", i);
            break;
        }
    }

    llama_sampler_free(sampler);
    LOGI("Generated %zu chars", result.size());
    return utf8ToJString(env, result);
}

// ── nativeGenerateStreaming ────────────────────────────────────────────────────
// Same generation, but emits each token piece to a Kotlin TokenSink as it's produced
// (real streaming). If TokenSink.onToken(piece) returns false, generation stops early
// (used by the refusal gate to abort). Still returns the full text.
JNIEXPORT jstring JNICALL
Java_com_wsvdmeer_pwncompanion_ai_GgufInference_nativeGenerateStreaming(
        JNIEnv* env, jobject /*thiz*/,
        jlong jHandle, jstring jPrompt, jint maxTokens, jfloat temperature, jobject jSink) {

    auto* h = reinterpret_cast<ModelHandle*>(jHandle);
    if (!h || !h->model || !h->ctx) {
        LOGE("nativeGenerateStreaming: null handle");
        return env->NewStringUTF("");
    }

    const char* promptStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptStr);
    env->ReleaseStringUTFChars(jPrompt, promptStr);

    if (!feedPrompt(h, prompt)) return env->NewStringUTF("");

    // Resolve TokenSink.onToken(String):Boolean once. Runs on the calling (IO) thread,
    // so this JNIEnv is valid for the callbacks below.
    jmethodID onToken = nullptr;
    if (jSink != nullptr) {
        jclass sinkCls = env->GetObjectClass(jSink);
        if (sinkCls != nullptr) {
            onToken = env->GetMethodID(sinkCls, "onToken", "(Ljava/lang/String;)Z");
            env->DeleteLocalRef(sinkCls);
        }
    }

    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    llama_sampler* sampler = buildSampler(temperature);

    std::string result;
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int n = llama_token_to_piece(vocab, tok, piece, (int)sizeof(piece) - 1, 0, true);
        if (n > 0) {
            piece[n] = '\0';
            result += piece;
            if (onToken != nullptr) {
                jstring js = utf8ToJString(env, std::string(piece, n));
                jboolean cont = env->CallBooleanMethod(jSink, onToken, js);
                env->DeleteLocalRef(js);
                if (!cont) { LOGI("Streaming stopped early by sink"); break; }
            }
        }
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, next) != 0) {
            LOGW("llama_decode failed at token %d — stopping early", i);
            break;
        }
    }

    llama_sampler_free(sampler);
    LOGI("Streamed %zu chars", result.size());
    return utf8ToJString(env, result);
}

// ── nativeFree ────────────────────────────────────────────────────────────────
// Releases context and model weights.
JNIEXPORT void JNICALL
Java_com_wsvdmeer_pwncompanion_ai_GgufInference_nativeFree(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong jHandle) {

    auto* h = reinterpret_cast<ModelHandle*>(jHandle);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
    LOGI("Model freed");
}

} // extern "C"

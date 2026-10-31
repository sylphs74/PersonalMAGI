// PocketMAGI native engine: thin JNI layer over llama.cpp (+ mtmd for vision).
// One model is resident at a time; Kotlin loads -> generates -> unloads per sage.
// All entry points must be called from a single thread (see LlamaBridge.kt).

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <csignal>
#include <fcntl.h>
#include <unistd.h>
#include <exception>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

#define TAG "PocketMAGI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int N_BATCH = 512;

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mctx = nullptr;
    common_chat_templates_ptr tmpls;
    int n_ctx = 4096;
};

Engine g;
bool g_on_gpu = false;
std::atomic<bool> g_cancel{false};
std::string g_last_stats;

// ---- crash diagnostics: everything the engine logs also goes to a file the app can show later
int g_log_fd = -1;

void log_write(const char * text) {
    if (g_log_fd < 0 || !text) return;
    const size_t n = strlen(text);
    if (n) { ssize_t w = write(g_log_fd, text, n); (void) w; }
}

void log_cb(ggml_log_level level, const char * text, void * /*user*/) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_print(prio, TAG, "%s", text);
    if (level != GGML_LOG_LEVEL_DEBUG) log_write(text);
}

void on_ggml_abort(const char * msg) {
    log_write("\n*** GGML ABORT: ");
    log_write(msg);
    log_write("\n");
    __android_log_print(ANDROID_LOG_FATAL, TAG, "GGML ABORT: %s", msg);
}

struct sigaction g_old_actions[32];

void on_fatal_signal(int sig, siginfo_t * info, void * uctx) {
    // async-signal-safe only: write() the signal number, then let the default handler run
    char buf[64] = "\n*** FATAL SIGNAL ";
    size_t len = strlen(buf);
    int v = sig; char digits[8]; int nd = 0;
    do { digits[nd++] = static_cast<char>('0' + v % 10); v /= 10; } while (v && nd < 7);
    while (nd) buf[len++] = digits[--nd];
    buf[len++] = '\n';
    if (g_log_fd >= 0) { ssize_t w = write(g_log_fd, buf, len); (void) w; }
    sigaction(sig, &g_old_actions[sig], nullptr);
    if (g_old_actions[sig].sa_flags & SA_SIGINFO) {
        if (g_old_actions[sig].sa_sigaction) { g_old_actions[sig].sa_sigaction(sig, info, uctx); return; }
    } else if (g_old_actions[sig].sa_handler != SIG_DFL && g_old_actions[sig].sa_handler != SIG_IGN) {
        g_old_actions[sig].sa_handler(sig); return;
    }
    raise(sig);
}

void install_crash_handlers() {
    const int sigs[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT};
    for (int sig : sigs) {
        struct sigaction sa {};
        sa.sa_sigaction = on_fatal_signal;
        sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
        sigemptyset(&sa.sa_mask);
        sigaction(sig, &sa, &g_old_actions[sig]);
    }
}

bool abort_cb(void * /*data*/) { return g_cancel.load(); }

void free_engine() {
    if (g.mctx) { mtmd_free(g.mctx); g.mctx = nullptr; }
    g.tmpls.reset();
    if (g.ctx) { llama_free(g.ctx); g.ctx = nullptr; }
    if (g.model) { llama_model_free(g.model); g.model = nullptr; }
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Kotlin strings arrive as UTF-8 byte arrays to avoid JNI "modified UTF-8" issues with emoji.
std::string jbytes(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

void throw_rt(JNIEnv * env, const std::string & msg) {
    LOGE("%s", msg.c_str());
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, msg.c_str());
}

// Length of the longest prefix of s that is complete UTF-8 (incomplete tail is kept for later).
size_t utf8_complete_prefix(const std::string & s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const auto c = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if      ((c & 0x80) == 0x00) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else { i += 1; continue; }  // stray byte: pass through
        if (i + len > n) break;
        i += len;
    }
    return i;
}

struct Formatted {
    std::string prompt;
    std::vector<std::string> stops;
    std::string how;   // which template path produced the prompt (shown in the stats)
};

// Turn/role markers of common chat templates. If a model writes one of these as plain text it has
// left its own turn (often a template mismatch); everything from there on is discarded.
const char * const kAlwaysStop[] = {
    "<|im_end|>", "<|im_start|>", "<|eot_id|>", "<|start_header_id|>", "<|end|>", "<|endoftext|>",
    "<end_of_turn>", "<start_of_turn>", "<|user|>", "<|assistant|>", "<|system|>", "</s>",
    "<\xc2\xa6im_end\xc2\xa6>", "<\xc2\xa6im_start\xc2\xa6>",
    "\n### 指示", "\n### 応答", "\n### 入力", "\nUSER:", "\nASSISTANT:",
};

// Render [system,user] with the model's own chat template. Some templates reject a
// system role, some jinja templates fail to parse; fall back step by step.
Formatted format_prompt(const std::string & system, const std::string & user) {
    struct Attempt { bool jinja; bool merge_system; };
    const Attempt attempts[] = { {true, false}, {true, true}, {false, false}, {false, true} };
    const char * const names[] = { "jinja", "jinja+merge", "legacy", "legacy+merge" };
    int k = 0;

    for (const auto & at : attempts) {
        try {
            common_chat_templates_inputs in;
            in.add_generation_prompt = true;
            in.use_jinja = at.jinja;
            in.enable_thinking = false;
            if (!system.empty() && !at.merge_system) {
                common_chat_msg sm; sm.role = "system"; sm.content = system;
                in.messages.push_back(sm);
            }
            common_chat_msg um; um.role = "user";
            um.content = (!system.empty() && at.merge_system) ? system + "\n\n" + user : user;
            in.messages.push_back(um);

            auto params = common_chat_templates_apply(g.tmpls.get(), in);
            Formatted f;
            f.prompt = params.prompt.str();
            f.stops = params.additional_stops;
            f.how = names[k];
            if (!f.prompt.empty()) return f;
        } catch (const std::exception & e) {
            LOGW("chat template attempt (jinja=%d merge=%d) failed: %s", at.jinja, at.merge_system, e.what());
        }
        k++;
    }
    Formatted f;
    f.prompt = (system.empty() ? "" : system + "\n\n") + "### 質問:\n" + user + "\n\n### 回答:\n";
    f.stops = {"### 質問", "\n### "};
    f.how = "fallback";
    return f;
}

bool is_gpu_type(ggml_backend_dev_t d) {
    const auto t = ggml_backend_dev_type(d);
    return t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

// Adreno model number from a description like "QUALCOMM Adreno(TM) 644", or -1.
int adreno_number(const char * desc) {
    const char * p = desc ? strstr(desc, "Adreno") : nullptr;
    if (!p) return -1;
    for (p += 6; *p; p++) {
        if (*p >= '0' && *p <= '9') return atoi(p);
    }
    return -1;
}

// Phone Adreno 6xx drivers are known to break the OpenCL backend (crashes mid-generation),
// so only Adreno 7xx and newer are used. Non-Adreno OpenCL devices are not used either.
bool gpu_usable(ggml_backend_dev_t d) {
    if (!is_gpu_type(d)) return false;
    const int n = adreno_number(ggml_backend_dev_description(d));
    return n >= 700;
}

bool is_gpu(ggml_backend_dev_t d) { return gpu_usable(d); }

std::string gpu_list() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * d = ggml_backend_dev_get(i);
        if (!gpu_usable(d)) continue;
        if (!out.empty()) out += "\n";
        out += std::string(ggml_backend_dev_name(d)) + ": " + ggml_backend_dev_description(d);
    }
    return out;
}

std::string unusable_gpu_list() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * d = ggml_backend_dev_get(i);
        if (!is_gpu_type(d) || gpu_usable(d)) continue;
        if (!out.empty()) out += ", ";
        out += ggml_backend_dev_description(d);
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeInit(JNIEnv * env, jclass, jstring jLibDir, jstring jCacheDir, jstring jLogPath) {
    const std::string log_path = jstr(env, jLogPath);
    if (!log_path.empty()) g_log_fd = open(log_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    install_crash_handlers();
    ggml_set_abort_callback(on_ggml_abort);
    llama_log_set(log_cb, nullptr);
    mtmd_helper_log_set(log_cb, nullptr);
    // compiled OpenCL kernels are cached here, so only the first GPU load pays the compile cost
    const std::string cache = jstr(env, jCacheDir);
    if (!cache.empty()) setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache.c_str(), 1);
    const std::string dir = jstr(env, jLibDir);
    LOGI("loading ggml backends from %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

// GPU devices found by the loaded ggml backends, one per line ("" when none).
JNIEXPORT jstring JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeGpuDevices(JNIEnv * env, jclass) {
    return env->NewStringUTF(gpu_list().c_str());
}

// GPUs that were found but are deliberately not used (e.g. Adreno 6xx).
JNIEXPORT jstring JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeUnusableGpus(JNIEnv * env, jclass) {
    return env->NewStringUTF(unusable_gpu_list().c_str());
}

// What the currently loaded model runs on.
JNIEXPORT jstring JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeBackend(JNIEnv * env, jclass) {
    if (!g.model) return env->NewStringUTF("");
    if (!g_on_gpu) return env->NewStringUTF("CPU");
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * d = ggml_backend_dev_get(i);
        if (is_gpu(d)) return env->NewStringUTF((std::string("GPU ") + ggml_backend_dev_description(d)).c_str());
    }
    return env->NewStringUTF("CPU");
}

// Returns null on success, otherwise an error message.
JNIEXPORT jstring JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeLoad(JNIEnv * env, jclass, jstring jModel, jstring jMmproj,
                                                 jint nCtx, jint nThreads, jint imageMaxTokens, jboolean useGpu) {
    free_engine();
    g_on_gpu = useGpu && !gpu_list().empty();
    g_cancel.store(false);
    const std::string model_path = jstr(env, jModel);
    const std::string mmproj_path = jstr(env, jMmproj);

    // Explicit device list: the usable GPU when on GPU, otherwise none at all, so that
    // an unsupported GPU (e.g. Adreno 6xx) is never touched, not even for op offload.
    static std::vector<ggml_backend_dev_t> devs;
    devs.clear();
    if (g_on_gpu) {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            auto * d = ggml_backend_dev_get(i);
            if (gpu_usable(d)) { devs.push_back(d); break; }
        }
    }
    devs.push_back(nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.devices = devs.data();
    mp.n_gpu_layers = g_on_gpu ? 999 : 0;  // all layers on the GPU when one is available
    g.model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!g.model) return env->NewStringUTF("モデルの読み込みに失敗しました");

    g.n_ctx = std::max(1024, static_cast<int>(nCtx));
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = g.n_ctx;
    cp.n_batch = N_BATCH;
    cp.n_ubatch = N_BATCH;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.op_offload = g_on_gpu;
    // OpenCL flash-attention kernels crashed on Adreno 825 (Gemma 4, head dim 256/512): plain attention instead
    cp.flash_attn_type = g_on_gpu ? LLAMA_FLASH_ATTN_TYPE_DISABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.offload_kqv = g_on_gpu;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = nullptr;
    g.ctx = llama_init_from_model(g.model, cp);
    if (!g.ctx) { free_engine(); return env->NewStringUTF("推論コンテキストの作成に失敗しました（メモリ不足の可能性）"); }

    g.tmpls = common_chat_templates_init(g.model, "");

    if (!mmproj_path.empty()) {
        mtmd_context_params mpp = mtmd_context_params_default();
        mpp.use_gpu = g_on_gpu;
        mpp.flash_attn_type = g_on_gpu ? LLAMA_FLASH_ATTN_TYPE_DISABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;
        mpp.n_threads = nThreads;
        mpp.print_timings = true;
        mpp.warmup = false;
        if (imageMaxTokens > 0) mpp.image_max_tokens = imageMaxTokens;
        {
            // non-causal vision models need a whole image inside one ubatch
            auto mem = mtmd_get_memory_usage(mmproj_path.c_str(), mpp);
            if (mem.use_non_causal && mem.image_max_tokens > N_BATCH) {
                mpp.image_max_tokens = N_BATCH;
                mpp.image_min_tokens = std::min(mpp.image_min_tokens, N_BATCH);
            }
        }
        g.mctx = mtmd_init_from_file(mmproj_path.c_str(), g.model, mpp);
        if (!g.mctx) { free_engine(); return env->NewStringUTF("画像エンコーダ(mmproj)の読み込みに失敗しました"); }
        if (!mtmd_support_vision(g.mctx)) { free_engine(); return env->NewStringUTF("このmmprojは画像入力に対応していません"); }
    }
    LOGI("loaded %s (ctx=%d threads=%d vision=%d gpu=%d)", model_path.c_str(), g.n_ctx, (int) nThreads, g.mctx != nullptr, g_on_gpu);
    return nullptr;
}

JNIEXPORT void JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeUnload(JNIEnv *, jclass) {
    free_engine();
}

JNIEXPORT void JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeCancel(JNIEnv *, jclass) {
    g_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeLastStats(JNIEnv * env, jclass) {
    return env->NewStringUTF(g_last_stats.c_str());
}

// system/user: UTF-8 bytes. images: encoded JPEG/PNG bytes (may be null).
// listener.onToken(byte[]) -> boolean (false = stop). Returns full UTF-8 output.
JNIEXPORT jbyteArray JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeGenerate(JNIEnv * env, jclass, jbyteArray jSystem, jbyteArray jUser,
                                                     jbyteArray jPrefill, jobjectArray jImages, jint maxTokens, jfloat temp,
                                                     jobject listener) {
    if (!g.model || !g.ctx) { throw_rt(env, "モデルが読み込まれていません"); return nullptr; }
    g_cancel.store(false);
    const auto t0 = std::chrono::steady_clock::now();

    const int n_images = jImages ? env->GetArrayLength(jImages) : 0;
    if (n_images > 0 && !g.mctx) { throw_rt(env, "このモデルは画像入力に対応していません"); return nullptr; }

    std::string user = jbytes(env, jUser);
    if (n_images > 0) {
        std::string markers;
        for (int i = 0; i < n_images; i++) markers += mtmd_default_marker();
        user = markers + "\n" + user;
    }
    Formatted fmt = format_prompt(jbytes(env, jSystem), user);
    // Start of the assistant's reply written by the app (e.g. "判定: "): small models then
    // continue in the required format instead of improvising their own layout.
    fmt.prompt += jbytes(env, jPrefill);
    for (const char * m : kAlwaysStop) fmt.stops.emplace_back(m);

    const llama_vocab * vocab = llama_model_get_vocab(g.model);
    bool add_special = true;
    const llama_token bos = llama_vocab_bos(vocab);
    if (bos != LLAMA_TOKEN_NULL) {
        const std::string bos_txt = common_token_to_piece(vocab, bos, true);
        if (!bos_txt.empty() && fmt.prompt.rfind(bos_txt, 0) == 0) add_special = false;
    }

    llama_memory_clear(llama_get_memory(g.ctx), true);
    llama_pos n_past = 0;
    size_t n_prompt = 0;

    if (n_images > 0) {
        std::vector<mtmd_bitmap *> bitmaps;
        auto free_bitmaps = [&]() { for (auto * b : bitmaps) mtmd_bitmap_free(b); bitmaps.clear(); };
        for (int i = 0; i < n_images; i++) {
            auto arr = static_cast<jbyteArray>(env->GetObjectArrayElement(jImages, i));
            const std::string buf = jbytes(env, arr);
            env->DeleteLocalRef(arr);
            auto w = mtmd_helper_bitmap_init_from_buf(g.mctx, reinterpret_cast<const unsigned char *>(buf.data()),
                                                      buf.size(), false, mtmd_helper_init_opt_default());
            if (w.video_ctx) mtmd_helper_video_free(w.video_ctx);
            if (!w.bitmap) { free_bitmaps(); throw_rt(env, "画像のデコードに失敗しました"); return nullptr; }
            bitmaps.push_back(w.bitmap);
        }
        std::vector<const mtmd_bitmap *> cbm(bitmaps.begin(), bitmaps.end());
        mtmd_input_text txt{fmt.prompt.c_str(), fmt.prompt.size(), add_special, true};
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        int32_t r = mtmd_tokenize(g.mctx, chunks, &txt, cbm.data(), cbm.size());
        if (r != 0) {
            mtmd_input_chunks_free(chunks); free_bitmaps();
            throw_rt(env, "画像付きプロンプトのトークン化に失敗しました (" + std::to_string(r) + ")");
            return nullptr;
        }
        n_prompt = mtmd_helper_get_n_tokens(chunks);
        if (static_cast<int>(n_prompt) + maxTokens + 8 > g.n_ctx) {
            mtmd_input_chunks_free(chunks); free_bitmaps();
            throw_rt(env, "入力が長すぎます（" + std::to_string(n_prompt) + "トークン）。フレーム数を減らしてください");
            return nullptr;
        }
        llama_pos new_n_past = 0;
        r = mtmd_helper_eval_chunks(g.mctx, g.ctx, chunks, 0, 0, N_BATCH, true, &new_n_past);
        mtmd_input_chunks_free(chunks);
        free_bitmaps();
        if (r != 0) {
            throw_rt(env, g_cancel.load() ? "中断されました" : "画像の評価に失敗しました (" + std::to_string(r) + ")");
            return nullptr;
        }
        n_past = new_n_past;
    } else {
        const auto tokens = common_tokenize(g.ctx, fmt.prompt, add_special, true);
        n_prompt = tokens.size();
        if (static_cast<int>(n_prompt) + maxTokens + 8 > g.n_ctx) {
            throw_rt(env, "入力が長すぎます（" + std::to_string(n_prompt) + "トークン）");
            return nullptr;
        }
        common_batch batch(g.ctx);
        for (size_t i = 0; i < tokens.size(); i += N_BATCH) {
            const size_t n = std::min(static_cast<size_t>(N_BATCH), tokens.size() - i);
            batch.clear();
            for (size_t j = 0; j < n; j++) {
                const bool last = (i + j == tokens.size() - 1);
                batch.add(tokens[i + j], static_cast<llama_pos>(i + j), 0, last);
            }
            if (llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                throw_rt(env, g_cancel.load() ? "中断されました" : "プロンプトの評価に失敗しました");
                return nullptr;
            }
        }
        n_past = static_cast<llama_pos>(tokens.size());
    }
    const auto t1 = std::chrono::steady_clock::now();

    jmethodID on_token = nullptr;
    if (listener) {
        jclass lcls = env->GetObjectClass(listener);
        on_token = env->GetMethodID(lcls, "onToken", "([B)Z");
        env->DeleteLocalRef(lcls);
    }

    common_params_sampling sp;
    sp.temp = temp;
    sp.top_k = 40;
    sp.top_p = 0.9f;
    sp.min_p = 0.05f;
    sp.penalty_repeat = 1.1f;
    sp.penalty_last_n = 128;
    common_sampler * smpl = common_sampler_init(g.model, sp);
    if (!smpl) { throw_rt(env, "サンプラーの初期化に失敗しました"); return nullptr; }

    common_batch batch(g.ctx);
    std::string out;
    std::string pending;
    size_t emitted = 0;
    int n_gen = 0;
    bool stopped_by_string = false;

    for (int i = 0; i < maxTokens; i++) {
        if (g_cancel.load()) break;
        if (n_past >= g.n_ctx - 1) break;

        const llama_token tok = common_sampler_sample(smpl, g.ctx, -1);
        common_sampler_accept(smpl, tok, true);
        if (llama_vocab_is_eog(vocab, tok) || llama_vocab_is_control(vocab, tok)) break;
        n_gen++;

        pending += common_token_to_piece(g.ctx, tok, false);
        const size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            out += pending.substr(0, ok);
            pending.erase(0, ok);

            for (const auto & s : fmt.stops) {
                if (s.empty()) continue;
                const size_t pos = out.find(s);
                if (pos != std::string::npos) { out.resize(pos); stopped_by_string = true; break; }
            }
            if (stopped_by_string) break;

            // stream only what cannot still turn into a stop string
            size_t safe = out.size();
            for (const auto & s : fmt.stops) {
                for (size_t k = std::min(s.size() - 1, out.size()); k > 0; k--) {
                    if (out.compare(out.size() - k, k, s, 0, k) == 0) { safe = std::min(safe, out.size() - k); break; }
                }
            }
            if (on_token && safe > emitted) {
                jbyteArray jp = to_jbytes(env, out.substr(emitted, safe - emitted));
                emitted = safe;
                const jboolean cont = env->CallBooleanMethod(listener, on_token, jp);
                env->DeleteLocalRef(jp);
                if (env->ExceptionCheck() || !cont) break;
            }
        }

        batch.clear();
        batch.add(tok, n_past++, 0, true);
        if (llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) break;
    }
    common_sampler_free(smpl);

    const auto t2 = std::chrono::steady_clock::now();
    const double pp_s = std::chrono::duration<double>(t1 - t0).count();
    const double tg_s = std::chrono::duration<double>(t2 - t1).count();
    char buf[224];
    snprintf(buf, sizeof(buf), "prompt %zu tok / %.1fs, gen %d tok / %.1fs (%.1f tok/s) tmpl=%s",
             n_prompt, pp_s, n_gen, tg_s, tg_s > 0 ? n_gen / tg_s : 0.0, fmt.how.c_str());
    g_last_stats = buf;
    LOGI("%s", buf);

    if (env->ExceptionCheck()) return nullptr;
    return to_jbytes(env, out);
}

}  // extern "C"

extern "C" JNIEXPORT void JNICALL
Java_jp_pocketmagi_engine_LlamaBridge_nativeMark(JNIEnv * env, jclass, jstring jText) {
    const std::string t = "\n=== " + jstr(env, jText) + "\n";
    log_write(t.c_str());
}

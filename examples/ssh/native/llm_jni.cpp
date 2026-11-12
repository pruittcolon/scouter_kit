// The app's on-phone language and embedding models (Llm.kt): llama.cpp running GGUF files from the app's files
// (Liquid's LFM2.5 to answer and summarize, EmbeddingGemma for search by meaning). On the CPU (the S25's cores
// with dot-product and int8 matrix instructions); one model per handle, used by one thread at a time.
#include <android/log.h>
#include <jni.h>

#include <stdlib.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct Llm {
  llama_model *model = nullptr;
  llama_context *ctx = nullptr;
  const llama_vocab *vocab = nullptr;
  bool embed = false;
  std::atomic<bool> stop{false};
  // The last generate's work: prompt tokens and µs, generated tokens and µs (for speed and time estimates), and
  // what went wrong (0 nothing, 1 the prompt did not fit, 2 reading the prompt failed, 3 a token failed) with
  // llama_decode's code.
  int64_t last[6] = {0, 0, 0, 0, 0, 0};
};

int64_t now_us() {
  timespec t{};
  clock_gettime(CLOCK_MONOTONIC, &t);
  return int64_t(t.tv_sec) * 1000000 + t.tv_nsec / 1000;
}

void log_cb(ggml_log_level level, const char *text, void *) {
  // Warnings and errors, without the kernel compiler's progress dots and empty lines.
  if (level < GGML_LOG_LEVEL_WARN || !text) return;
  std::string t(text);
  while (!t.empty() && (t.back() == '\n' || t.back() == '.')) t.pop_back();
  if (!t.empty()) __android_log_print(ANDROID_LOG_WARN, "scouter", "llm: %s", t.c_str());
}

// llama.cpp's backends and its log, once.
void start() {
  static std::once_flag once;
  std::call_once(once, [] {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
  });
}

std::string utf8(JNIEnv *env, jstring s) {
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string out(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}

// Java strings as UTF-8 bytes (a String's modified UTF-8 would mangle characters outside the BMP).
std::string bytes(JNIEnv *env, jbyteArray a) {
  jsize n = env->GetArrayLength(a);
  std::string out(static_cast<size_t>(n), '\0');
  env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
  return out;
}

jbyteArray jbytes(JNIEnv *env, const std::string &s) {
  jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
  env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
  return a;
}

std::vector<llama_token> tokenize(const llama_vocab *v, const std::string &s, bool add_special, bool parse_special) {
  int n = -llama_tokenize(v, s.data(), static_cast<int32_t>(s.size()), nullptr, 0, add_special, parse_special);
  if (n <= 0) return {};
  std::vector<llama_token> t(static_cast<size_t>(n));
  if (llama_tokenize(v, s.data(), static_cast<int32_t>(s.size()), t.data(), n, add_special, parse_special) < 0) return {};
  return t;
}

}  // namespace

extern "C" {

// A model from a GGUF file: n_ctx tokens of context, threads (generation; prompt batches use batch_threads), embed
// (an embedding model) or not, gpu_layers on the GPU (0: the CPU only; a build without a GPU backend ignores it).
// 0 if it failed.
JNIEXPORT jlong JNICALL Java_scouterkit_ssh_LlmNative_load(JNIEnv *env, jobject, jstring path, jint n_ctx, jint threads,
                                                          jint batch_threads, jboolean embed, jint gpu_layers) {
  start();
  auto mp = llama_model_default_params();
  mp.n_gpu_layers = gpu_layers;
  std::string p = utf8(env, path);
  llama_model *m = llama_model_load_from_file(p.c_str(), mp);
  if (!m) return 0;
  auto cp = llama_context_default_params();
  cp.n_ctx = static_cast<uint32_t>(n_ctx);
  cp.n_batch = embed ? static_cast<uint32_t>(n_ctx) : 512;
  cp.n_ubatch = embed ? static_cast<uint32_t>(n_ctx) : 512;
  cp.n_threads = threads;
  cp.n_threads_batch = batch_threads;
  cp.embeddings = embed;
  cp.no_perf = true;
  if (embed) cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;
  llama_context *c = llama_init_from_model(m, cp);
  if (!c) {
    llama_model_free(m);
    return 0;
  }
  auto *h = new Llm;
  h->model = m;
  h->ctx = c;
  h->vocab = llama_model_get_vocab(m);
  h->embed = embed;
  return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_LlmNative_free(JNIEnv *, jobject, jlong handle) {
  auto *h = reinterpret_cast<Llm *>(handle);
  if (!h) return;
  llama_free(h->ctx);
  llama_model_free(h->model);
  delete h;
}

// The threads for generation and for prompt batches, from now on.
JNIEXPORT void JNICALL Java_scouterkit_ssh_LlmNative_threads(JNIEnv *, jobject, jlong handle, jint threads, jint batch_threads) {
  auto *h = reinterpret_cast<Llm *>(handle);
  if (h) llama_set_n_threads(h->ctx, threads, batch_threads);
}

// An environment variable for llama.cpp, before its backends start (the OpenCL kernel cache's folder).
JNIEXPORT void JNICALL Java_scouterkit_ssh_LlmNative_env(JNIEnv *env, jobject, jstring key, jstring value) {
  setenv(utf8(env, key).c_str(), utf8(env, value).c_str(), 1);
}

// The last generate's prompt tokens, prompt µs, generated tokens, generation µs.
JNIEXPORT jlongArray JNICALL Java_scouterkit_ssh_LlmNative_timings(JNIEnv *env, jobject, jlong handle) {
  auto *h = reinterpret_cast<Llm *>(handle);
  jlongArray out = env->NewLongArray(6);
  if (h) env->SetLongArrayRegion(out, 0, 6, reinterpret_cast<const jlong *>(h->last));
  return out;
}

// The GPU devices llama.cpp sees ("" none): for the app to say where models run.
JNIEXPORT jstring JNICALL Java_scouterkit_ssh_LlmNative_devices(JNIEnv *env, jobject) {
  start();
  std::string out;
  for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
    ggml_backend_dev_t d = ggml_backend_dev_get(i);
    if (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU) continue;
    if (!out.empty()) out += ", ";
    out += ggml_backend_dev_description(d);
  }
  return env->NewStringUTF(out.c_str());
}

// Asks a running generation to end at its next token.
JNIEXPORT void JNICALL Java_scouterkit_ssh_LlmNative_stop(JNIEnv *, jobject, jlong handle) {
  auto *h = reinterpret_cast<Llm *>(handle);
  if (h) h->stop = true;
}

// Tokens in a text (to fit prompts into the context).
JNIEXPORT jint JNICALL Java_scouterkit_ssh_LlmNative_count(JNIEnv *env, jobject, jlong handle, jbyteArray text) {
  auto *h = reinterpret_cast<Llm *>(handle);
  if (!h) return -1;
  return static_cast<jint>(tokenize(h->vocab, bytes(env, text), false, true).size());
}

// Continues a prompt (UTF-8, with the model's chat markers in it): at most max_tokens, sampled at temp with min_p
// and a mild repetition penalty (greedy at temp 0). sink.piece(byte[]) gets each piece as it comes (false: stop).
// The whole answer, UTF-8; empty if the prompt did not fit or failed.
JNIEXPORT jbyteArray JNICALL Java_scouterkit_ssh_LlmNative_generate(JNIEnv *env, jobject, jlong handle, jbyteArray prompt,
                                                                    jint max_tokens, jfloat temp, jfloat min_p,
                                                                    jobject sink) {
  auto *h = reinterpret_cast<Llm *>(handle);
  if (!h || h->embed) return jbytes(env, "");
  h->stop = false;
  for (auto &x : h->last) x = 0;
  llama_memory_clear(llama_get_memory(h->ctx), true);
  auto toks = tokenize(h->vocab, bytes(env, prompt), true, true);
  const int n_ctx = static_cast<int>(llama_n_ctx(h->ctx));
  h->last[0] = static_cast<int64_t>(toks.size());
  if (toks.empty() || static_cast<int>(toks.size()) >= n_ctx - 16) {
    h->last[4] = 1;
    return jbytes(env, "");
  }
  const int nb = static_cast<int>(llama_n_batch(h->ctx));
  const int64_t t0 = now_us();
  for (size_t i = 0; i < toks.size(); i += static_cast<size_t>(nb)) {
    int n = std::min(nb, static_cast<int>(toks.size() - i));
    int rc = llama_decode(h->ctx, llama_batch_get_one(toks.data() + i, n));
    if (rc != 0) {
      h->last[1] = now_us() - t0;
      h->last[4] = 2;
      h->last[5] = rc;
      __android_log_print(ANDROID_LOG_WARN, "scouter", "llm: reading the prompt failed at token %zu: %d", i, rc);
      return jbytes(env, "");
    }
    if (h->stop) return jbytes(env, "");
  }
  h->last[0] = static_cast<int64_t>(toks.size());
  h->last[1] = now_us() - t0;
  const int64_t t1 = now_us();
  auto sp = llama_sampler_chain_default_params();
  sp.no_perf = true;
  llama_sampler *smpl = llama_sampler_chain_init(sp);
  if (temp <= 0.f) {
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
  } else {
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(h->vocab), 64, 1.05f, 0.f, 0.f));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
  }
  jclass cls = sink ? env->GetObjectClass(sink) : nullptr;
  jmethodID piece = cls ? env->GetMethodID(cls, "piece", "([B)Z") : nullptr;
  std::string out;
  std::string pending;  // bytes of a character not complete yet
  int used = static_cast<int>(toks.size());
  char buf[256];
  for (int i = 0; i < max_tokens && used < n_ctx - 1 && !h->stop; i++) {
    llama_token t = llama_sampler_sample(smpl, h->ctx, -1);
    if (llama_vocab_is_eog(h->vocab, t)) break;
    int n = llama_token_to_piece(h->vocab, t, buf, sizeof(buf), 0, false);
    if (n > 0) {
      std::string s(buf, static_cast<size_t>(n));
      out += s;
      pending += s;
      // Hand over only whole UTF-8 characters.
      size_t keep = 0;
      for (size_t k = pending.size(); k > 0 && k + 4 > pending.size(); k--) {
        unsigned char c = static_cast<unsigned char>(pending[k - 1]);
        if ((c & 0xC0) == 0x80) continue;
        size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        if (pending.size() - (k - 1) < need) keep = pending.size() - (k - 1);
        break;
      }
      std::string ready = pending.substr(0, pending.size() - keep);
      pending.erase(0, pending.size() - keep);
      if (piece && !ready.empty()) {
        jbyteArray a = jbytes(env, ready);
        jboolean go = env->CallBooleanMethod(sink, piece, a);
        env->DeleteLocalRef(a);
        if (env->ExceptionCheck()) {
          env->ExceptionClear();
          go = JNI_FALSE;
        }
        if (!go) break;
      }
    }
    int rc = llama_decode(h->ctx, llama_batch_get_one(&t, 1));
    if (rc != 0) {
      h->last[4] = 3;
      h->last[5] = rc;
      __android_log_print(ANDROID_LOG_WARN, "scouter", "llm: a token failed: %d", rc);
      break;
    }
    used++;
  }
  llama_sampler_free(smpl);
  h->last[2] = used - static_cast<int64_t>(toks.size());
  h->last[3] = now_us() - t1;
  return jbytes(env, out);
}

// The text's embedding (the model's pooling; EmbeddingGemma: mean, then its projection), not normalized. Null if
// it failed. The text is cut to the context.
JNIEXPORT jfloatArray JNICALL Java_scouterkit_ssh_LlmNative_embed(JNIEnv *env, jobject, jlong handle, jbyteArray text) {
  auto *h = reinterpret_cast<Llm *>(handle);
  if (!h || !h->embed) return nullptr;
  llama_memory_clear(llama_get_memory(h->ctx), true);
  auto toks = tokenize(h->vocab, bytes(env, text), true, false);
  const int n_ctx = static_cast<int>(llama_n_batch(h->ctx));
  if (toks.empty()) return nullptr;
  if (static_cast<int>(toks.size()) > n_ctx) toks.resize(static_cast<size_t>(n_ctx));
  llama_batch b = llama_batch_init(static_cast<int32_t>(toks.size()), 0, 1);
  for (size_t i = 0; i < toks.size(); i++) {
    b.token[i] = toks[i];
    b.pos[i] = static_cast<llama_pos>(i);
    b.n_seq_id[i] = 1;
    b.seq_id[i][0] = 0;
    b.logits[i] = true;
  }
  b.n_tokens = static_cast<int32_t>(toks.size());
  int rc = (llama_model_has_encoder(h->model) && !llama_model_has_decoder(h->model)) ? llama_encode(h->ctx, b)
                                                                                        : llama_decode(h->ctx, b);
  jfloatArray out = nullptr;
  if (rc == 0) {
    const float *e = llama_get_embeddings_seq(h->ctx, 0);
    int dim = llama_model_n_embd_out(h->model);
    if (dim <= 0) dim = llama_model_n_embd(h->model);
    if (e) {
      out = env->NewFloatArray(dim);
      env->SetFloatArrayRegion(out, 0, dim, e);
    }
  }
  llama_batch_free(b);
  return out;
}

}  // extern "C"

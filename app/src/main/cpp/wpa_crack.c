// Native WPA2 PMKID cracker — the hot path (PBKDF2-HMAC-SHA1, 4096 iters) in C, so a candidate
// costs one JNI call instead of ~8200 JCE Mac.doFinal() dispatches + allocations. Correctness is
// pinned to the same reference vector as the Kotlin cracker (WpaCrackerTest).
//
// Portable software SHA-1 for now (still a big win from killing the JVM/JCE overhead per candidate);
// an ARMv8 crypto-extension SHA-1 transform can slot into sha1_transform() later for more.

#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

// ---------- SHA-1 ----------
typedef struct { uint32_t h[5]; uint64_t len; uint8_t buf[64]; size_t idx; } sha1_ctx;

static inline uint32_t rol(uint32_t v, int b) { return (v << b) | (v >> (32 - b)); }

// Portable software transform (used everywhere; the only path on armeabi-v7a / no-crypto CPUs).
static void sha1_transform_sw(sha1_ctx *c, const uint8_t *p) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++)
        w[i] = ((uint32_t)p[i*4] << 24) | ((uint32_t)p[i*4+1] << 16) | ((uint32_t)p[i*4+2] << 8) | p[i*4+3];
    for (int i = 16; i < 80; i++)
        w[i] = rol(w[i-3] ^ w[i-8] ^ w[i-14] ^ w[i-16], 1);
    uint32_t a = c->h[0], b = c->h[1], cc = c->h[2], d = c->h[3], e = c->h[4];
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20)      { f = (b & cc) | ((~b) & d);           k = 0x5A827999; }
        else if (i < 40) { f = b ^ cc ^ d;                      k = 0x6ED9EBA1; }
        else if (i < 60) { f = (b & cc) | (b & d) | (cc & d);   k = 0x8F1BBCDC; }
        else             { f = b ^ cc ^ d;                      k = 0xCA62C1D6; }
        uint32_t t = rol(a, 5) + f + e + k + w[i];
        e = d; d = cc; cc = rol(b, 30); b = a; a = t;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d; c->h[4] += e;
}

// -1 = undecided, 0 = software, 1 = ARMv8 crypto-extension hardware SHA-1.
static int g_use_hw = -1;

#if defined(__aarch64__)
// Hardware SHA-1 block transform via the ARMv8 crypto extension (canonical intrinsic sequence).
__attribute__((target("+crypto")))
static void sha1_transform_hw(sha1_ctx *ctx, const uint8_t *data) {
    uint32x4_t ABCD, ABCD_SAVED, TMP0, TMP1, MSG0, MSG1, MSG2, MSG3;
    uint32_t E0, E0_SAVED, E1;
    const uint32x4_t K0 = vdupq_n_u32(0x5A827999), K1 = vdupq_n_u32(0x6ED9EBA1),
                     K2 = vdupq_n_u32(0x8F1BBCDC), K3 = vdupq_n_u32(0xCA62C1D6);

    ABCD = vld1q_u32(&ctx->h[0]); E0 = ctx->h[4];
    ABCD_SAVED = ABCD; E0_SAVED = E0;

    MSG0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 0)));
    MSG1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 16)));
    MSG2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 32)));
    MSG3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 48)));
    TMP0 = vaddq_u32(MSG0, K0); TMP1 = vaddq_u32(MSG1, K0);

    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1cq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, K0); MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1cq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, K0); MSG0 = vsha1su1q_u32(MSG0, MSG3); MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1cq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, K0); MSG1 = vsha1su1q_u32(MSG1, MSG0); MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1cq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, K1); MSG2 = vsha1su1q_u32(MSG2, MSG1); MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1cq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, K1); MSG3 = vsha1su1q_u32(MSG3, MSG2); MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, K1); MSG0 = vsha1su1q_u32(MSG0, MSG3); MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, K1); MSG1 = vsha1su1q_u32(MSG1, MSG0); MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, K1); MSG2 = vsha1su1q_u32(MSG2, MSG1); MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, K2); MSG3 = vsha1su1q_u32(MSG3, MSG2); MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, K2); MSG0 = vsha1su1q_u32(MSG0, MSG3); MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1mq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, K2); MSG1 = vsha1su1q_u32(MSG1, MSG0); MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1mq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, K2); MSG2 = vsha1su1q_u32(MSG2, MSG1); MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1mq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, K2); MSG3 = vsha1su1q_u32(MSG3, MSG2); MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1mq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, K3); MSG0 = vsha1su1q_u32(MSG0, MSG3); MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1mq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, K3); MSG1 = vsha1su1q_u32(MSG1, MSG0); MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, K3); MSG2 = vsha1su1q_u32(MSG2, MSG1); MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, K3); MSG3 = vsha1su1q_u32(MSG3, MSG2); MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, K3); MSG0 = vsha1su1q_u32(MSG0, MSG3);
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E0, TMP0);
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0)); ABCD = vsha1pq_u32(ABCD, E1, TMP1);

    E0 += E0_SAVED; ABCD = vaddq_u32(ABCD_SAVED, ABCD);
    vst1q_u32(&ctx->h[0], ABCD); ctx->h[4] = E0;
}

// Enable HW only if the CPU advertises SHA-1 AND it reproduces the SHA-1("abc") test vector, so a
// bad build/CPU silently uses software instead of ever returning a wrong hash.
static void ensure_hw(void) {
    if (g_use_hw >= 0) return;
    g_use_hw = 0;
    if (getauxval(AT_HWCAP) & HWCAP_SHA1) {
        sha1_ctx c;
        c.h[0] = 0x67452301; c.h[1] = 0xEFCDAB89; c.h[2] = 0x98BADCFE;
        c.h[3] = 0x10325476; c.h[4] = 0xC3D2E1F0;
        uint8_t blk[64]; memset(blk, 0, 64);
        blk[0] = 'a'; blk[1] = 'b'; blk[2] = 'c'; blk[3] = 0x80; blk[63] = 0x18;  // "abc" padded
        sha1_transform_hw(&c, blk);
        uint8_t out[20];
        for (int i = 0; i < 5; i++) {
            out[i*4] = c.h[i] >> 24; out[i*4+1] = c.h[i] >> 16; out[i*4+2] = c.h[i] >> 8; out[i*4+3] = c.h[i];
        }
        static const uint8_t exp[20] = {
            0xa9,0x99,0x3e,0x36,0x47,0x06,0x81,0x6a,0xba,0x3e,
            0x25,0x71,0x78,0x50,0xc2,0x6c,0x9c,0xd0,0xd8,0x9d };
        if (memcmp(out, exp, 20) == 0) g_use_hw = 1;
    }
}
#else
static void ensure_hw(void) { g_use_hw = 0; }
#endif

static inline void sha1_transform(sha1_ctx *c, const uint8_t *p) {
#if defined(__aarch64__)
    if (g_use_hw == 1) { sha1_transform_hw(c, p); return; }
#endif
    sha1_transform_sw(c, p);
}

static void sha1_init(sha1_ctx *c) {
    c->h[0] = 0x67452301; c->h[1] = 0xEFCDAB89; c->h[2] = 0x98BADCFE;
    c->h[3] = 0x10325476; c->h[4] = 0xC3D2E1F0; c->len = 0; c->idx = 0;
}

static void sha1_update(sha1_ctx *c, const uint8_t *d, size_t n) {
    c->len += n;
    while (n) {
        size_t k = 64 - c->idx; if (k > n) k = n;
        memcpy(c->buf + c->idx, d, k); c->idx += k; d += k; n -= k;
        if (c->idx == 64) { sha1_transform(c, c->buf); c->idx = 0; }
    }
}

static void sha1_final(sha1_ctx *c, uint8_t *out) {
    uint64_t bits = c->len * 8;
    uint8_t pad = 0x80; sha1_update(c, &pad, 1);
    uint8_t z = 0; while (c->idx != 56) sha1_update(c, &z, 1);
    uint8_t lb[8]; for (int i = 0; i < 8; i++) lb[i] = (uint8_t)(bits >> (56 - 8*i));
    sha1_update(c, lb, 8);
    for (int i = 0; i < 5; i++) {
        out[i*4] = c->h[i] >> 24; out[i*4+1] = c->h[i] >> 16;
        out[i*4+2] = c->h[i] >> 8; out[i*4+3] = c->h[i];
    }
}

static void sha1_oneshot(const uint8_t *d, size_t n, uint8_t *out) {
    sha1_ctx c; sha1_init(&c); sha1_update(&c, d, n); sha1_final(&c, out);
}

// ---------- HMAC-SHA1 (key padded once, reused across the PBKDF2 iteration loop) ----------
typedef struct { sha1_ctx ictx, octx; } hmac_ctx;

static void hmac_init(hmac_ctx *h, const uint8_t *key, size_t klen) {
    uint8_t k[64];
    if (klen > 64) { uint8_t t[20]; sha1_oneshot(key, klen, t); memcpy(k, t, 20); memset(k + 20, 0, 44); }
    else { memcpy(k, key, klen); memset(k + klen, 0, 64 - klen); }
    uint8_t ip[64], op[64];
    for (int i = 0; i < 64; i++) { ip[i] = k[i] ^ 0x36; op[i] = k[i] ^ 0x5c; }
    sha1_init(&h->ictx); sha1_update(&h->ictx, ip, 64);
    sha1_init(&h->octx); sha1_update(&h->octx, op, 64);
}

// out[20] = HMAC(key, msg), using the pre-absorbed pads (copy-then-continue).
static void hmac_compute(const hmac_ctx *h, const uint8_t *msg, size_t mlen, uint8_t *out) {
    sha1_ctx c = h->ictx; sha1_update(&c, msg, mlen); uint8_t inner[20]; sha1_final(&c, inner);
    sha1_ctx o = h->octx; sha1_update(&o, inner, 20); sha1_final(&o, out);
}

// ---------- WPA2 PMK + PMKID ----------
// PMK = PBKDF2-HMAC-SHA1(passphrase, essid, 4096, 32). dkLen 32 = first 32 of two 20-byte blocks.
static void wpa_pmk(const uint8_t *pw, size_t pwlen, const uint8_t *salt, size_t slen, int iter, uint8_t out32[32]) {
    hmac_ctx h; hmac_init(&h, pw, pwlen);
    for (int block = 1; block <= 2; block++) {
        uint8_t sb[36];                       // essid (<=32) + 4-byte big-endian block index
        size_t sl = slen; memcpy(sb, salt, slen);
        sb[sl++] = (block >> 24) & 0xff; sb[sl++] = (block >> 16) & 0xff;
        sb[sl++] = (block >> 8) & 0xff;  sb[sl++] = block & 0xff;
        uint8_t u[20]; hmac_compute(&h, sb, sl, u);
        uint8_t t[20]; memcpy(t, u, 20);
        for (int i = 1; i < iter; i++) { hmac_compute(&h, u, 20, u); for (int j = 0; j < 20; j++) t[j] ^= u[j]; }
        int off = (block - 1) * 20;
        for (int j = 0; j < 20 && off + j < 32; j++) out32[off + j] = t[j];
    }
}

// PMKID = HMAC-SHA1(PMK, "PMK Name" || AP || STA)[:16]
static void wpa_pmkid(const uint8_t pmk[32], const uint8_t ap[6], const uint8_t sta[6], uint8_t out16[16]) {
    hmac_ctx h; hmac_init(&h, pmk, 32);
    uint8_t msg[20]; memcpy(msg, "PMK Name", 8); memcpy(msg + 8, ap, 6); memcpy(msg + 14, sta, 6);
    uint8_t full[20]; hmac_compute(&h, msg, 20, full); memcpy(out16, full, 16);
}

// ---------- WPA2 EAPOL (WPA*02, key-version 2 / HMAC-SHA1) ----------
// KCK = PTK[0:16], via the IEEE 802.11 PRF. B is the caller-prepared sorted material
// (min(AA,SA)||max(AA,SA)||min(ANonce,SNonce)||max(ANonce,SNonce)). Block i=0 alone yields the KCK.
static void wpa_kck(const uint8_t pmk[32], const uint8_t *b, size_t blen, uint8_t out16[16]) {
    hmac_ctx h; hmac_init(&h, pmk, 32);
    uint8_t msg[128];
    size_t o = 22; memcpy(msg, "Pairwise key expansion", 22);   // A (no null terminator)
    msg[o++] = 0x00;                                            // separator
    memcpy(msg + o, b, blen); o += blen;
    msg[o++] = 0x00;                                            // block index i = 0
    uint8_t full[20]; hmac_compute(&h, msg, o, full); memcpy(out16, full, 16);
}

// MIC (key-version 2) = HMAC-SHA1(KCK, eapol)[:16], eapol having its MIC field pre-zeroed.
static void wpa_mic(const uint8_t kck[16], const uint8_t *eapol, size_t elen, uint8_t out16[16]) {
    hmac_ctx h; hmac_init(&h, kck, 16);
    uint8_t full[20]; hmac_compute(&h, eapol, elen, full); memcpy(out16, full, 16);
}

// ---------- JNI ----------
// Returns the index of the first candidate whose PMKID matches, or -1 if none in this batch.
JNIEXPORT jint JNICALL
Java_com_wsvdmeer_pwncompanion_crack_NativeWpaCracker_crackBatch(
        JNIEnv *env, jclass clazz,
        jbyteArray essid_, jbyteArray ap_, jbyteArray sta_, jbyteArray pmkid_, jint iter,
        jobjectArray candidates) {
    ensure_hw();   // pick HW vs software SHA-1 once (validated), then reuse
    jsize elen = (*env)->GetArrayLength(env, essid_);
    if (elen > 32) elen = 32;
    uint8_t essid[32], ap[6], sta[6], target[16];
    (*env)->GetByteArrayRegion(env, essid_, 0, elen, (jbyte *)essid);
    (*env)->GetByteArrayRegion(env, ap_, 0, 6, (jbyte *)ap);
    (*env)->GetByteArrayRegion(env, sta_, 0, 6, (jbyte *)sta);
    (*env)->GetByteArrayRegion(env, pmkid_, 0, 16, (jbyte *)target);

    jsize n = (*env)->GetArrayLength(env, candidates);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, candidates, i);
        if (!s) continue;
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        jint result = -1;
        if (c) {
            size_t clen = strlen(c);
            uint8_t pmk[32]; wpa_pmk((const uint8_t *)c, clen, essid, elen, iter, pmk);
            uint8_t pid[16]; wpa_pmkid(pmk, ap, sta, pid);
            if (memcmp(pid, target, 16) == 0) result = i;
            (*env)->ReleaseStringUTFChars(env, s, c);
        }
        (*env)->DeleteLocalRef(env, s);
        if (result >= 0) return result;
    }
    return -1;
}

// EAPOL (WPA*02, key-version 2). Same PBKDF2 hot path, then PTK/KCK + MIC per candidate.
// Returns the index of the first candidate whose MIC matches, or -1 if none in this batch.
JNIEXPORT jint JNICALL
Java_com_wsvdmeer_pwncompanion_crack_NativeWpaCracker_crackBatchEapol(
        JNIEnv *env, jclass clazz,
        jbyteArray essid_, jbyteArray ap_, jbyteArray sta_, jbyteArray mic_,
        jbyteArray anonce_, jbyteArray eapol_, jint iter, jobjectArray candidates) {
    ensure_hw();
    jsize elen = (*env)->GetArrayLength(env, essid_);
    if (elen > 32) elen = 32;
    uint8_t essid[32], ap[6], sta[6], target[16], anonce[32];
    (*env)->GetByteArrayRegion(env, essid_, 0, elen, (jbyte *)essid);
    (*env)->GetByteArrayRegion(env, ap_, 0, 6, (jbyte *)ap);
    (*env)->GetByteArrayRegion(env, sta_, 0, 6, (jbyte *)sta);
    (*env)->GetByteArrayRegion(env, mic_, 0, 16, (jbyte *)target);
    (*env)->GetByteArrayRegion(env, anonce_, 0, 32, (jbyte *)anonce);

    jsize eaplen = (*env)->GetArrayLength(env, eapol_);
    if (eaplen < 49 || eaplen > 512) return -1;   // malformed, or too big for the stack buffer
    uint8_t eapol[512];
    (*env)->GetByteArrayRegion(env, eapol_, 0, eaplen, (jbyte *)eapol);

    // SNonce is the frame's key-nonce field (bytes 17..48). Build the PRF's sorted material B once
    // per handshake: min(AA,SA) || max(AA,SA) || min(ANonce,SNonce) || max(ANonce,SNonce).
    uint8_t snonce[32]; memcpy(snonce, eapol + 17, 32);
    uint8_t B[76];
    const uint8_t *m1 = ap, *m2 = sta;
    if (memcmp(ap, sta, 6) > 0) { m1 = sta; m2 = ap; }
    memcpy(B, m1, 6); memcpy(B + 6, m2, 6);
    const uint8_t *n1 = anonce, *n2 = snonce;
    if (memcmp(anonce, snonce, 32) > 0) { n1 = snonce; n2 = anonce; }
    memcpy(B + 12, n1, 32); memcpy(B + 44, n2, 32);

    jsize n = (*env)->GetArrayLength(env, candidates);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, candidates, i);
        if (!s) continue;
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        jint result = -1;
        if (c) {
            size_t clen = strlen(c);
            uint8_t pmk[32]; wpa_pmk((const uint8_t *)c, clen, essid, elen, iter, pmk);
            uint8_t kck[16]; wpa_kck(pmk, B, 76, kck);
            uint8_t mic[16]; wpa_mic(kck, eapol, eaplen, mic);
            if (memcmp(mic, target, 16) == 0) result = i;
            (*env)->ReleaseStringUTFChars(env, s, c);
        }
        (*env)->DeleteLocalRef(env, s);
        if (result >= 0) return result;
    }
    return -1;
}

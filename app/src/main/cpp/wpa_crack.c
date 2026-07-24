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

// ---------- SHA-1 ----------
typedef struct { uint32_t h[5]; uint64_t len; uint8_t buf[64]; size_t idx; } sha1_ctx;

static inline uint32_t rol(uint32_t v, int b) { return (v << b) | (v >> (32 - b)); }

static void sha1_transform(sha1_ctx *c, const uint8_t *p) {
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

// ---------- JNI ----------
// Returns the index of the first candidate whose PMKID matches, or -1 if none in this batch.
JNIEXPORT jint JNICALL
Java_com_wsvdmeer_pwncompanion_crack_NativeWpaCracker_crackBatch(
        JNIEnv *env, jclass clazz,
        jbyteArray essid_, jbyteArray ap_, jbyteArray sta_, jbyteArray pmkid_, jint iter,
        jobjectArray candidates) {
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

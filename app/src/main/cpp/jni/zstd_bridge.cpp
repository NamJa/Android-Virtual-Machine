// EP8.3 — on-device zstd decompression backed by NDK-built libzstd.
//
// zstd-jni ships no Android-loadable native, so the guest-ROM tar.zst path needs
// its own zstd on device. This one-shot file decompressor streams a .zst file to
// a plain .tar file using the ZSTD streaming API; the Kotlin side then reads the
// .tar with commons-compress. (Streaming straight into the tar reader is a later
// optimization; decompress-to-file keeps the JNI surface tiny and robust.)

#include <jni.h>

#include <cstdio>
#include <string>
#include <vector>

#include <zstd.h>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_storage_NativeZstd_nativeDecompressFile(
    JNIEnv* env, jclass, jstring jSrc, jstring jDst) {
    const char* sp = env->GetStringUTFChars(jSrc, nullptr);
    const char* dp = env->GetStringUTFChars(jDst, nullptr);
    const std::string src = sp ? sp : "";
    const std::string dst = dp ? dp : "";
    if (sp) env->ReleaseStringUTFChars(jSrc, sp);
    if (dp) env->ReleaseStringUTFChars(jDst, dp);

    auto done = [&](const std::string& err) -> jstring { return env->NewStringUTF(err.c_str()); };

    FILE* in = fopen(src.c_str(), "rb");
    if (!in) return done("open_src_failed");
    FILE* out = fopen(dst.c_str(), "wb");
    if (!out) {
        fclose(in);
        return done("open_dst_failed");
    }
    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (!dctx) {
        fclose(in);
        fclose(out);
        return done("dctx_failed");
    }

    const size_t inCap = ZSTD_DStreamInSize();
    const size_t outCap = ZSTD_DStreamOutSize();
    std::vector<char> inBuf(inCap), outBuf(outCap);
    std::string err;

    size_t readBytes;
    while ((readBytes = fread(inBuf.data(), 1, inCap, in)) > 0) {
        ZSTD_inBuffer input{inBuf.data(), readBytes, 0};
        while (input.pos < input.size) {
            ZSTD_outBuffer output{outBuf.data(), outCap, 0};
            const size_t ret = ZSTD_decompressStream(dctx, &output, &input);
            if (ZSTD_isError(ret)) {
                err = ZSTD_getErrorName(ret);
                break;
            }
            if (output.pos > 0 && fwrite(outBuf.data(), 1, output.pos, out) != output.pos) {
                err = "write_failed";
                break;
            }
        }
        if (!err.empty()) break;
    }

    ZSTD_freeDCtx(dctx);
    fclose(in);
    if (fclose(out) != 0 && err.empty()) err = "close_failed";
    return done(err); // "" => success
}

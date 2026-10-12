package com.xiaoai.sbc_decoder

/**
 * JNI shim for libsbc.so copied from the official app (lib/arm64-v8a). The package and method name must match
 * the exported symbol Java_com_xiaoai_sbc_1decoder_SBCDecoder_decodeSBC. Decodes mSBC frames to 16 kHz s16le PCM.
 */
class SBCDecoder {
    /** Returns the number of PCM bytes written to [output] (size it at least 6x [length], as the official app does). */
    external fun decodeSBC(input: ByteArray, length: Int, output: ByteArray): Int

    companion object {
        val available: Boolean = runCatching { System.loadLibrary("sbc") }.isSuccess
    }
}

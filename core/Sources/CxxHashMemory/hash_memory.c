// libswiftCore.so in Swift's Android SDK was built against a libc++ newer than the NDK's: it
// calls std::__ndk1::__hash_memory, which the NDK's libc++_shared.so (r28, r29) doesn't export.
// The SDK's lib_FoundationICU.so happens to carry one, which is why it loaded while the app
// shipped ICU. This is that function, exported from the core library (the root of the tree
// System.loadLibrary loads, searched before libswiftCore's own dependencies) and from the
// probe. Its only callers are the runtime's hash tables inside this process, so any good hash
// serves; this is 64-bit FNV-1a, with a final mix.
#if defined(__ANDROID__)
#include <stddef.h>
#include <stdint.h>

__attribute__((visibility("default"), used))
size_t moonlight_hash_memory(const void *bytes, size_t count) __asm__("_ZNSt6__ndk113__hash_memoryEPKvm");

size_t moonlight_hash_memory(const void *bytes, size_t count) {
    const unsigned char *p = bytes;
    uint64_t h = 0xcbf29ce484222325ull;
    for (size_t i = 0; i < count; i++) { h ^= p[i]; h *= 0x100000001b3ull; }
    h ^= h >> 33; h *= 0xff51afd7ed558ccdull; h ^= h >> 33;
    return (size_t)h;
}
#endif

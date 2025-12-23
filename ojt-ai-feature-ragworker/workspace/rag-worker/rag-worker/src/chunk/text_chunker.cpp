#include "chunk/text_chunker.hpp"

#include <algorithm>
#include <iomanip>
#include <sstream>
#include <openssl/sha.h>

namespace ragworker {
namespace {
constexpr std::size_t kChunkSize = 800;
constexpr std::size_t kChunkOverlap = 150;

std::string sha256_hex(const std::string& content) {
    unsigned char hash[SHA256_DIGEST_LENGTH];
    SHA256_CTX ctx;
    SHA256_Init(&ctx);
    SHA256_Update(&ctx, reinterpret_cast<const unsigned char*>(content.data()), content.size());
    SHA256_Final(hash, &ctx);

    std::ostringstream oss;
    oss << std::hex << std::setfill('0');
    for (int i = 0; i < SHA256_DIGEST_LENGTH; ++i) {
        oss << std::setw(2) << static_cast<int>(hash[i]);
    }
    return oss.str();
}

}  // namespace

std::vector<Chunk> chunk_text(const std::string& text) {
    std::vector<Chunk> chunks;
    if (text.empty()) {
        return chunks;
    }

    std::size_t start = 0;
    int seq = 0;
    while (start < text.size()) {
        const std::size_t end = std::min(text.size(), start + kChunkSize);
        const std::string slice = text.substr(start, end - start);
        Chunk chunk;
        chunk.seq_no = seq++;
        chunk.content = slice;
        chunk.content_sha256 = sha256_hex(slice);
        chunks.emplace_back(std::move(chunk));

        if (end == text.size()) {
            break;
        }
        const std::size_t next_start = end > kChunkOverlap ? end - kChunkOverlap : end;
        if (next_start <= start) {
            start = end;
        } else {
            start = next_start;
        }
    }
    return chunks;
}

}  // namespace ragworker

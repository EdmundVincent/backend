#pragma once

#include <string>

namespace ragworker {

struct Chunk {
    int seq_no = 0;
    std::string content;
    std::string content_sha256;
};

}  // namespace ragworker

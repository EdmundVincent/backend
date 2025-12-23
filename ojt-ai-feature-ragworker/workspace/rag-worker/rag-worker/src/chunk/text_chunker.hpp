#pragma once

#include <string>
#include <vector>

#include "chunk/chunk.hpp"

namespace ragworker {

std::vector<Chunk> chunk_text(const std::string& text);

}  // namespace ragworker

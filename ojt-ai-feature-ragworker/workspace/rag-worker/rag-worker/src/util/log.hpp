#pragma once

#include <string_view>

namespace ragworker::log {

enum class Level { Info, Error };

void write(Level level, std::string_view message);
void info(std::string_view message);
void error(std::string_view message);

}  // namespace ragworker::log

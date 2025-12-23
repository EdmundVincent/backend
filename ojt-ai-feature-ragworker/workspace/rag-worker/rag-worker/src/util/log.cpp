#include "util/log.hpp"

#include <iostream>
#include <mutex>

#include "util/time.hpp"

namespace ragworker::log {
namespace {

std::mutex& log_mutex() {
    static std::mutex mutex;
    return mutex;
}

const char* to_string(Level level) {
    switch (level) {
        case Level::Info:
            return "INFO";
        case Level::Error:
            return "ERROR";
    }
    return "UNKNOWN";
}

}  // namespace

void write(Level level, std::string_view message) {
    const std::string timestamp = time::current_time_iso8601();
    auto& stream = (level == Level::Error) ? std::cerr : std::cout;

    std::lock_guard<std::mutex> lock(log_mutex());
    stream << '[' << timestamp << "][" << to_string(level) << "] " << message << std::endl;
}

void info(std::string_view message) { write(Level::Info, message); }

void error(std::string_view message) { write(Level::Error, message); }

}  // namespace ragworker::log

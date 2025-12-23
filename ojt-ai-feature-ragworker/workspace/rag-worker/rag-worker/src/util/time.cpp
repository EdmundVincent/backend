#include "util/time.hpp"

#include <chrono>
#include <ctime>
#include <iomanip>
#include <sstream>

namespace ragworker::time {

std::string current_time_iso8601() {
    using clock = std::chrono::system_clock;
    const auto now = clock::now();
    const auto now_seconds = std::chrono::time_point_cast<std::chrono::seconds>(now);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - now_seconds).count();

    const std::time_t time_t_value = clock::to_time_t(now_seconds);
    std::tm tm_buffer{};
#if defined(_WIN32)
    gmtime_s(&tm_buffer, &time_t_value);
#else
    gmtime_r(&time_t_value, &tm_buffer);
#endif

    std::ostringstream oss;
    oss << std::put_time(&tm_buffer, "%Y-%m-%dT%H:%M:%S");
    oss << '.' << std::setw(3) << std::setfill('0') << ms << 'Z';
    return oss.str();
}

}  // namespace ragworker::time

#pragma once

#include <string>

namespace ragworker::time {

// Returns the current UTC time formatted as ISO-8601 with millisecond precision.
std::string current_time_iso8601();

}  // namespace ragworker::time

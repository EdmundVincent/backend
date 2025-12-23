#pragma once

#include <string>

#include "config/config.hpp"

namespace ragworker {

int run_http_server(const Config& config, const std::string& host, int port);

}  // namespace ragworker

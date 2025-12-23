#pragma once

#include <string>
#include <vector>

namespace ragworker {

struct HttpRequest {
    std::string method = "GET";
    std::string url;
    std::vector<std::string> headers;
    std::string body;
    long timeout_seconds = 30;
};

struct HttpResponse {
    long status = 0;
    std::string body;
};

HttpResponse perform_http_request(const HttpRequest& request);

}  // namespace ragworker

#include "net/http_client.hpp"

#include <curl/curl.h>

#include <stdexcept>
#include <string_view>

namespace ragworker {
namespace {

class CurlGlobal {
public:
    CurlGlobal() { curl_global_init(CURL_GLOBAL_DEFAULT); }
    ~CurlGlobal() { curl_global_cleanup(); }
};

CurlGlobal& global_curl() {
    static CurlGlobal global;
    return global;
}

size_t write_callback(char* ptr, size_t size, size_t nmemb, void* userdata) {
    const size_t total = size * nmemb;
    auto* buffer = static_cast<std::string*>(userdata);
    buffer->append(ptr, total);
    return total;
}

}  // namespace

HttpResponse perform_http_request(const HttpRequest& request) {
    global_curl();
    CURL* curl = curl_easy_init();
    if (!curl) {
        throw std::runtime_error("failed to initialize curl");
    }

    std::string response_body;
    curl_easy_setopt(curl, CURLOPT_URL, request.url.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_callback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response_body);
    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, request.method.c_str());
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    if (request.timeout_seconds > 0) {
        curl_easy_setopt(curl, CURLOPT_TIMEOUT, request.timeout_seconds);
    }

    struct curl_slist* headers = nullptr;
    for (const auto& header : request.headers) {
        headers = curl_slist_append(headers, header.c_str());
    }
    if (headers != nullptr) {
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    }

    if (!request.body.empty()) {
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, request.body.c_str());
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, request.body.size());
    } else if (request.method == "POST" || request.method == "PUT" || request.method == "PATCH") {
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, 0L);
    }

    CURLcode code = curl_easy_perform(curl);
    if (code != CURLE_OK) {
        if (headers != nullptr) {
            curl_slist_free_all(headers);
        }
        curl_easy_cleanup(curl);
        throw std::runtime_error(std::string{"curl request failed: "} + curl_easy_strerror(code));
    }

    long status_code = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status_code);

    if (headers != nullptr) {
        curl_slist_free_all(headers);
    }
    curl_easy_cleanup(curl);
    return HttpResponse{status_code, std::move(response_body)};
}

}  // namespace ragworker

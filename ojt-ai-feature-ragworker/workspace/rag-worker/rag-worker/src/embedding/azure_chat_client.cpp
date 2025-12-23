#include "embedding/azure_chat_client.hpp"

#include <chrono>
#include <stdexcept>
#include <thread>

#include <nlohmann/json.hpp>

#include "config/config.hpp"
#include "net/http_client.hpp"

namespace ragworker {
namespace {

nlohmann::json build_responses_body(const std::string& system_prompt,
                                    const std::string& user_prompt,
                                    int max_tokens) {
    nlohmann::json system;
    system["role"] = "system";
    system["content"] = {{{"type", "text"}, {"text", system_prompt}}};

    nlohmann::json user;
    user["role"] = "user";
    user["content"] = {{{"type", "text"}, {"text", user_prompt}}};

    nlohmann::json body;
    body["input"] = nlohmann::json::array();
    body["input"].push_back(system);
    body["input"].push_back(user);
    body["temperature"] = 0.0;
    body["max_output_tokens"] = max_tokens;
    return body;
}

std::string extract_responses_text(const nlohmann::json& json) {
    if (!json.contains("output") || !json["output"].is_array()) {
        throw std::runtime_error("azure chat response missing output array");
    }

    std::string combined;
    for (const auto& entry : json["output"]) {
        if (!entry.contains("content") || !entry["content"].is_array()) {
            continue;
        }
        for (const auto& block : entry["content"]) {
            if (!block.contains("type") || !block["type"].is_string()) {
                continue;
            }
            if (block["type"].get<std::string>() != "text") {
                continue;
            }
            if (!block.contains("text") || !block["text"].is_string()) {
                continue;
            }
            if (!combined.empty()) {
                combined.push_back('\n');
            }
            combined += block["text"].get<std::string>();
        }
    }

    if (combined.empty()) {
        throw std::runtime_error("azure chat response missing text");
    }
    return combined;
}

std::string extract_chat_completions_text(const nlohmann::json& json) {
    if (!json.contains("choices") || !json["choices"].is_array() || json["choices"].empty()) {
        throw std::runtime_error("chat completions response missing choices");
    }
    const auto& choice = json["choices"][0];
    if (!choice.contains("message") || !choice["message"].is_object()) {
        throw std::runtime_error("chat completions response missing message");
    }
    const auto& message = choice["message"];
    if (!message.contains("content")) {
        throw std::runtime_error("chat completions message missing content");
    }
    if (message["content"].is_array()) {
        std::string combined;
        for (const auto& part : message["content"]) {
            if (part.contains("text") && part["text"].is_string()) {
                if (!combined.empty()) {
                    combined.push_back('\n');
                }
                combined += part["text"].get<std::string>();
            }
        }
        if (!combined.empty()) {
            return combined;
        }
        throw std::runtime_error("chat completions content array missing text entries");
    }
    if (!message["content"].is_string()) {
        throw std::runtime_error("chat completions content not string");
    }
    return message["content"].get<std::string>();
}

nlohmann::json build_chat_completions_body(const std::string& system_prompt,
                                           const std::string& user_prompt,
                                           const std::string& model,
                                           int max_tokens) {
    nlohmann::json body;
    body["model"] = model;
    body["temperature"] = 0.0;
    body["max_tokens"] = max_tokens;
    body["messages"] = nlohmann::json::array({
        {{"role", "system"}, {"content", system_prompt}},
        {{"role", "user"}, {"content", user_prompt}},
    });
    return body;
}

}  // namespace

AzureChatClient::AzureChatClient(const Config& config)
    : url_(config.azure_chat_url()),
      api_key_(config.azure_api_key()),
      deployment_(config.azure_chat_deployment()),
      max_output_tokens_(512),
      use_responses_(false),
      use_chat_completions_(false) {
    if (url_.empty()) {
        throw std::runtime_error("missing Azure chat configuration (endpoint/deployment/version)");
    }
    if (api_key_.empty()) {
        throw std::runtime_error("missing AZURE_OPENAI_API_KEY");
    }

    if (url_.find("responses") != std::string::npos) {
        use_responses_ = true;
    } else if (url_.find("chat/completions") != std::string::npos) {
        use_chat_completions_ = true;
    }

    if (!use_responses_ && !use_chat_completions_) {
        throw std::runtime_error("unsupported Azure chat endpoint (must contain /responses or /chat/completions)");
    }
    if (use_chat_completions_ && deployment_.empty()) {
        throw std::runtime_error("AZURE_OPENAI_CHAT_DEPLOYMENT required for chat completions");
    }
}

std::string AzureChatClient::complete(const std::string& system_prompt,
                                      const std::string& user_prompt) const {
    nlohmann::json body;
    if (use_responses_) {
        body = build_responses_body(system_prompt, user_prompt, max_output_tokens_);
    } else {
        body = build_chat_completions_body(system_prompt, user_prompt, deployment_, max_output_tokens_);
    }
    const HttpRequest base_request{
        .method = "POST",
        .url = url_,
        .headers = {"Content-Type: application/json", "api-key: " + api_key_},
        .body = body.dump(),
        .timeout_seconds = 45,
    };

    const int max_attempts = 3;
    for (int attempt = 0; attempt < max_attempts; ++attempt) {
        try {
            const auto response = perform_http_request(base_request);
            if (response.status == 200) {
                auto json = nlohmann::json::parse(response.body);
                return use_responses_ ? extract_responses_text(json) : extract_chat_completions_text(json);
            }

            const std::string body_preview =
                response.body.size() > 512 ? response.body.substr(0, 512) + "..." : response.body;
            if (response.status == 401 || response.status == 403) {
                throw std::runtime_error("azure chat unauthorized (status " + std::to_string(response.status) +
                                         ") body: " + body_preview);
            }

            if ((response.status == 429 || response.status >= 500) && attempt + 1 < max_attempts) {
                const auto backoff = std::chrono::seconds(1 << attempt);
                std::this_thread::sleep_for(backoff);
                continue;
            }

            throw std::runtime_error("azure chat failed with status " + std::to_string(response.status) +
                                     " body: " + body_preview);
        } catch (const nlohmann::json::exception& ex) {
            throw std::runtime_error(std::string{"failed to parse azure chat response: "} + ex.what());
        }
    }

    throw std::runtime_error("azure chat failed after retries");
}

}  // namespace ragworker

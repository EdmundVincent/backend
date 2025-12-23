#pragma once

#include <string>

namespace ragworker {

class Config;

class AzureChatClient {
public:
    explicit AzureChatClient(const Config& config);

    std::string complete(const std::string& system_prompt,
                         const std::string& user_prompt) const;

private:
    std::string url_;
    std::string api_key_;
    std::string deployment_;
    int max_output_tokens_;
    bool use_responses_;
    bool use_chat_completions_;
};

}  // namespace ragworker

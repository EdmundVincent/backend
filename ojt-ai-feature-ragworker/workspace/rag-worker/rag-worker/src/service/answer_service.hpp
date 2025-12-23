#pragma once

#include <string>
#include <vector>

#include "config/config.hpp"
#include "embedding/azure_chat_client.hpp"
#include "service/search_service.hpp"

namespace ragworker {

struct AnswerSource {
    std::string doc_id;
    int seq_no = 0;
    double score = 0.0;
};

struct AnswerResponse {
    std::string answer;
    std::vector<AnswerSource> sources;
};

class AnswerService {
public:
    explicit AnswerService(const Config& config);

    AnswerResponse answer(const std::string& tenant_id,
                          const std::string& kb_id,
                          const std::string& question,
                          int topk);

private:
    SearchService search_service_;
    AzureChatClient chat_client_;
};

}  // namespace ragworker

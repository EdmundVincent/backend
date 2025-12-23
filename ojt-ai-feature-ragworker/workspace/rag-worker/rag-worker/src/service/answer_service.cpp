#include "service/answer_service.hpp"

#include <sstream>

namespace ragworker {
namespace {

constexpr std::string_view kAnswerSystemPrompt = R"(You are a retrieval-augmented assistant.
Answer the question ONLY using the provided context.
If the answer is not contained in the context, say "I don't know based on the provided documents."
Do NOT use any outside knowledge.
Cite sources using the provided document identifiers.)";

std::string build_context_block(const std::vector<QdrantSearchResult>& hits) {
    std::ostringstream oss;
    for (std::size_t i = 0; i < hits.size(); ++i) {
        const auto& hit = hits[i];
        oss << "[doc_id=" << hit.doc_id << " seq_no=" << hit.seq_no << " score=" << hit.score << "]\n"
            << hit.content;
        if (i + 1 < hits.size()) {
            oss << "\n\n";
        }
    }
    return oss.str();
}

std::vector<AnswerSource> build_sources(const std::vector<QdrantSearchResult>& hits) {
    std::vector<AnswerSource> sources;
    sources.reserve(hits.size());
    for (const auto& hit : hits) {
        sources.push_back(AnswerSource{hit.doc_id, hit.seq_no, hit.score});
    }
    return sources;
}

}  // namespace

AnswerService::AnswerService(const Config& config)
    : search_service_(config),
      chat_client_(config) {}

AnswerResponse AnswerService::answer(const std::string& tenant_id,
                                     const std::string& kb_id,
                                     const std::string& question,
                                     int topk) {
    auto search_result = search_service_.search(tenant_id, kb_id, question, topk);
    const auto& hits = search_result.results;

    if (hits.empty()) {
        return AnswerResponse{
            .answer = "I don't know based on the provided documents.",
            .sources = {},
        };
    }

    std::string user_prompt = build_context_block(hits);
    if (!user_prompt.empty()) {
        user_prompt.append("\n\n");
    }
    user_prompt.append("Question:\n");
    user_prompt.append(question);

    const std::string answer = chat_client_.complete(std::string{kAnswerSystemPrompt}, user_prompt);
    return AnswerResponse{
        .answer = answer,
        .sources = build_sources(hits),
    };
}

}  // namespace ragworker

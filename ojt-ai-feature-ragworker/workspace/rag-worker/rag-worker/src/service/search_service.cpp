#include "service/search_service.hpp"

#include <stdexcept>

#include "embedding/azure_embedder.hpp"

namespace ragworker {
namespace {

std::string build_collection(const std::string& tenant_id, const std::string& kb_id) {
    if (tenant_id.empty() || kb_id.empty()) {
        throw std::runtime_error("tenant_id and kb_id must not be empty");
    }
    return tenant_id + "__" + kb_id;
}

}  // namespace

SearchService::SearchService(const Config& config)
    : config_(config),
      embedder_(config),
      qdrant_client_(config.qdrant_url()) {}

SearchResponse SearchService::search(const std::string& tenant_id,
                                     const std::string& kb_id,
                                     const std::string& query,
                                     int topk) {
    if (query.empty()) {
        throw std::runtime_error("search query must not be empty");
    }
    if (topk <= 0) {
        throw std::runtime_error("topk must be positive");
    }

    const auto embedding = embedder_.embed(query);
    if (embedding.empty()) {
        throw std::runtime_error("embedding returned empty vector");
    }

    const std::string collection = build_collection(tenant_id, kb_id);
    auto results = qdrant_client_.search(collection, embedding, topk);

    SearchResponse response;
    response.collection = collection;
    response.topk = topk;
    response.results = std::move(results);
    return response;
}

}  // namespace ragworker

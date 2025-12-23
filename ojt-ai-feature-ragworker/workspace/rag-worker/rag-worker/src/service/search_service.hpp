#pragma once

#include <string>
#include <vector>

#include "config/config.hpp"
#include "embedding/azure_embedder.hpp"
#include "qdrant/qdrant_client.hpp"

namespace ragworker {

struct SearchResponse {
    std::string collection;
    int topk = 0;
    std::vector<QdrantSearchResult> results;
};

class SearchService {
public:
    explicit SearchService(const Config& config);

    SearchResponse search(const std::string& tenant_id,
                          const std::string& kb_id,
                          const std::string& query,
                          int topk);

private:
    const Config& config_;
    AzureEmbedder embedder_;
    QdrantClient qdrant_client_;
};

}  // namespace ragworker

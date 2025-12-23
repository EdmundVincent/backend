#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

namespace ragworker {

struct QdrantSearchResult {
    double score = 0.0;
    std::string doc_id;
    int seq_no = 0;
    std::string content;
};

class QdrantClient {
public:
    explicit QdrantClient(std::string base_url);

    void ensure_collection(const std::string& collection_name);
    void upsert_point(const std::string& collection_name,
                      std::uint64_t point_id,
                      const std::vector<float>& vector,
                      const nlohmann::json& payload);
    std::vector<QdrantSearchResult> search(const std::string& collection_name,
                                           const std::vector<float>& vector,
                                           int top_k);

private:
    std::string base_url_;

    std::string collection_url(const std::string& collection_name) const;
};

}  // namespace ragworker

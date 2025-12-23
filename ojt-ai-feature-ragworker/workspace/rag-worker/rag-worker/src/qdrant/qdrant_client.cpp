#include "qdrant/qdrant_client.hpp"

#include <stdexcept>

#include "net/http_client.hpp"

namespace ragworker
{
    namespace
    {

        std::string ensure_no_trailing_slash(std::string url)
        {
            while (!url.empty() && url.back() == '/')
            {
                url.pop_back();
            }
            return url;
        }

    }

    QdrantClient::QdrantClient(std::string base_url) : base_url_(ensure_no_trailing_slash(std::move(base_url))) {}

    std::string QdrantClient::collection_url(const std::string &collection_name) const
    {
        return base_url_ + "/collections/" + collection_name;
    }

    void QdrantClient::ensure_collection(const std::string &collection_name)
    {
        const HttpRequest get_request{
            .method = "GET",
            .url = collection_url(collection_name),
            .headers = {},
            .body = {},
            .timeout_seconds = 10,
        };

        HttpResponse response = perform_http_request(get_request);
        if (response.status == 200)
        {
            return;
        }
        if (response.status != 404)
        {
            throw std::runtime_error("qdrant collection check failed with status " + std::to_string(response.status) +
                                     " body: " + response.body);
        }

        nlohmann::json body;
        body["vectors"] = {{"size", 3072}, {"distance", "Cosine"}};
        const HttpRequest put_request{
            .method = "PUT",
            .url = collection_url(collection_name),
            .headers = {"Content-Type: application/json"},
            .body = body.dump(),
            .timeout_seconds = 10,
        };

        response = perform_http_request(put_request);
        if (response.status != 200)
        {
            throw std::runtime_error("failed to create qdrant collection: status " + std::to_string(response.status) +
                                     " body: " + response.body);
        }
    }

    void QdrantClient::upsert_point(const std::string &collection_name,
                                    std::uint64_t point_id,
                                    const std::vector<float> &vector,
                                    const nlohmann::json &payload)
    {
        nlohmann::json point;
        point["id"] = point_id;
        point["vector"] = vector;
        point["payload"] = payload;

        nlohmann::json body;
        body["points"] = nlohmann::json::array();
        body["points"].push_back(point);

        const HttpRequest request{
            .method = "PUT",
            .url = collection_url(collection_name) + "/points",
            .headers = {"Content-Type: application/json"},
            .body = body.dump(),
            .timeout_seconds = 15,
        };

        const auto response = perform_http_request(request);
        if (response.status != 200)
        {
            throw std::runtime_error("qdrant upsert failed with status " + std::to_string(response.status) +
                                     " body: " + response.body);
        }
    }

    std::vector<QdrantSearchResult> QdrantClient::search(const std::string &collection_name,
                                                         const std::vector<float> &vector,
                                                         int top_k)
    {
        if (top_k <= 0)
        {
            throw std::runtime_error("qdrant search requires top_k > 0");
        }

        nlohmann::json body;
        body["vector"] = vector;
        body["limit"] = top_k;
        body["with_payload"] = true;

        const HttpRequest request{
            .method = "POST",
            .url = collection_url(collection_name) + "/points/search",
            .headers = {"Content-Type: application/json"},
            .body = body.dump(),
            .timeout_seconds = 20,
        };

        const auto response = perform_http_request(request);
        if (response.status == 404)
        {
            throw std::runtime_error("qdrant collection not found: " + collection_name);
        }
        if (response.status != 200)
        {
            throw std::runtime_error("qdrant search failed with status " + std::to_string(response.status) +
                                     " body: " + response.body);
        }

        nlohmann::json json;
        try
        {
            json = nlohmann::json::parse(response.body);
        }
        catch (const nlohmann::json::exception &ex)
        {
            throw std::runtime_error(std::string{"failed to parse qdrant search response: "} + ex.what());
        }

        if (!json.contains("result") || !json["result"].is_array())
        {
            throw std::runtime_error("qdrant search response missing result array");
        }

        std::vector<QdrantSearchResult> results;
        for (const auto &item : json["result"])
        {
            if (!item.contains("score"))
            {
                throw std::runtime_error("qdrant search result missing score");
            }
            if (!item.contains("payload") || !item["payload"].is_object())
            {
                throw std::runtime_error("qdrant search result missing payload");
            }
            const auto &payload = item["payload"];
            if (!payload.contains("doc_id") || !payload["doc_id"].is_string())
            {
                throw std::runtime_error("qdrant payload missing doc_id");
            }
            if (!payload.contains("seq_no"))
            {
                throw std::runtime_error("qdrant payload missing seq_no");
            }
            if (!payload.contains("content") || !payload["content"].is_string())
            {
                throw std::runtime_error("qdrant payload missing content");
            }

            QdrantSearchResult result;
            result.score = item["score"].get<double>();
            result.doc_id = payload["doc_id"].get<std::string>();
            result.seq_no = payload["seq_no"].get<int>();
            result.content = payload["content"].get<std::string>();
            results.push_back(std::move(result));
        }

        return results;
    }

}

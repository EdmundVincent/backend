#include "embedding/azure_embedder.hpp"

#include <chrono>
#include <stdexcept>
#include <thread>

#include <nlohmann/json.hpp>

#include "config/config.hpp"
#include "net/http_client.hpp"

namespace ragworker
{

    namespace
    {
        constexpr int kExpectedDimensions = 3072;

        nlohmann::json build_request_body(const std::string &text)
        {
            nlohmann::json body;
            body["input"] = text;
            return body;
        }

    } // namespace

    AzureEmbedder::AzureEmbedder(const Config &config)
        : url_(config.azure_embedding_url()),
          api_key_(config.azure_api_key()),
          dimension_(kExpectedDimensions)
    {
        if (url_.empty())
        {
            throw std::runtime_error("環境変数を設定しておりません: AZURE_ENDPOINT, AZURE_EMBEDDING_DEPLOYMENT, AZURE_API_VERSION");
        }
        if (api_key_.empty())
        {
            throw std::runtime_error("APIキーが設定されていません: AZURE_API_KEY");
        }
    }

    std::vector<float> AzureEmbedder::embed(const std::string &text) const
    {
        if (text.empty())
        {
            return {};
        }

        const HttpRequest base_request{
            .method = "POST",
            .url = url_,
            .headers = {"Content-Type: application/json", "api-key: " + api_key_},
            .body = build_request_body(text).dump(),
            .timeout_seconds = 30,
        };

        const int max_attempts = 3;
        for (int attempt = 0; attempt < max_attempts; ++attempt)
        {
            try
            {
                const auto response = perform_http_request(base_request);
                if (response.status == 200)
                {
                    auto json = nlohmann::json::parse(response.body);
                    if (!json.contains("data") || json["data"].empty())
                    {
                        throw std::runtime_error("azure embedding response missing data");
                    }
                    const auto &embedding_json = json["data"][0]["embedding"];
                    if (!embedding_json.is_array())
                    {
                        throw std::runtime_error("azure embedding format invalid");
                    }
                    std::vector<float> embedding;
                    embedding.reserve(embedding_json.size());
                    for (const auto &value : embedding_json)
                    {
                        embedding.push_back(value.get<float>());
                    }
                    if (embedding.size() != static_cast<std::size_t>(dimension_))
                    {
                        throw std::runtime_error("unexpected embedding dimension: " + std::to_string(embedding.size()));
                    }
                    return embedding;
                }

                if (response.status == 401 || response.status == 403)
                {
                    throw std::runtime_error("azure embedding unauthorized (status " + std::to_string(response.status) + ')');
                }

                if ((response.status == 429 || response.status >= 500) && attempt + 1 < max_attempts)
                {
                    const auto backoff = std::chrono::seconds(1 << attempt);
                    std::this_thread::sleep_for(backoff);
                    continue;
                }

                throw std::runtime_error("azure embedding request failed with status " + std::to_string(response.status));
            }
            catch (const nlohmann::json::exception &ex)
            {
                throw std::runtime_error(std::string{"failed to parse azure embedding response: "} + ex.what());
            }
        }

        throw std::runtime_error("azure embedding failed after retries");
    }

} // namespace ragworker

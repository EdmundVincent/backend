#include "config/config.hpp"

#include <cstdlib>
#include <sstream>
#include <stdexcept>
#include <utility>

#include "util/log.hpp"

namespace ragworker
{
    namespace
    {

        std::string env_or_default(const char *name, const char *default_value)
        {
            if (const char *value = std::getenv(name); value && *value)
            {
                return value;
            }
            return default_value;
        }

    } // namespace

    Config::Config(std::string environment,
                   std::string pg_host,
                   std::string pg_port,
                   std::string pg_database,
                   std::string pg_user,
                   std::string pg_password,
                   std::string azure_endpoint,
                   std::string azure_api_key,
                   std::string azure_api_version,
                   std::string azure_embedding_deployment,
                   std::string azure_chat_deployment,
                   std::string azure_chat_api_version,
                   std::string azure_chat_endpoint_override,
                   std::string kafka_brokers,
                   std::string kafka_worker_group,
                   std::string qdrant_url)
        : environment_(std::move(environment)),
          pg_host_(std::move(pg_host)),
          pg_port_(std::move(pg_port)),
          pg_database_(std::move(pg_database)),
          pg_user_(std::move(pg_user)),
          pg_password_(std::move(pg_password)),
          azure_endpoint_(std::move(azure_endpoint)),
          azure_api_key_(std::move(azure_api_key)),
          azure_api_version_(std::move(azure_api_version)),
          azure_embedding_deployment_(std::move(azure_embedding_deployment)),
          azure_chat_deployment_(std::move(azure_chat_deployment)),
          azure_chat_api_version_(std::move(azure_chat_api_version)),
          azure_chat_endpoint_override_(std::move(azure_chat_endpoint_override)),
          kafka_brokers_(std::move(kafka_brokers)),
          kafka_worker_group_(std::move(kafka_worker_group)),
          qdrant_url_(std::move(qdrant_url)) {}

    Config Config::load()
    {
        auto chat_api_version = env_or_default("AZURE_OPENAI_CHAT_API_VERSION", "");
        if (chat_api_version.empty())
        {
            chat_api_version = env_or_default("AZURE_OPENAI_API_VERSION", "");
        }

        Config config{"local",
                      env_or_default("PGHOST", "postgres"),
                      env_or_default("PGPORT", "5432"),
                      env_or_default("PGDATABASE", "rag_db"),
                      env_or_default("PGUSER", "rag_user"),
                      env_or_default("PGPASSWORD", "rag_pass"),
                      env_or_default("AZURE_OPENAI_ENDPOINT", ""),
                      env_or_default("AZURE_OPENAI_API_KEY", ""),
                      env_or_default("AZURE_OPENAI_API_VERSION", ""),
                      env_or_default("AZURE_OPENAI_EMBEDDING_DEPLOYMENT", ""),
                      env_or_default("AZURE_OPENAI_CHAT_DEPLOYMENT", ""),
                      chat_api_version,
                      env_or_default("AZURE_OPENAI_CHAT_ENDPOINT", ""),
                      env_or_default("KAFKA_BROKERS", "redpanda:9092"),
                      env_or_default("KAFKA_WORKER_GROUP", "rag-core-worker"),
                      env_or_default("QDRANT_URL", "http://qdrant:6333")};
        log::info("config loaded");
        return config;
    }

    std::string Config::pg_conninfo() const
    {
        std::ostringstream oss;
        oss << "host=" << pg_host_;
        oss << " port=" << pg_port_;
        oss << " dbname=" << pg_database_;
        oss << " user=" << pg_user_;
        oss << " password=" << pg_password_;
        return oss.str();
    }

    std::string Config::azure_embedding_url() const
    {
        if (azure_endpoint_.empty())
        {
            return "";
        }
        if (azure_endpoint_.find("embeddings") != std::string::npos)
        {
            if (azure_endpoint_.find("api-version=") != std::string::npos || azure_api_version_.empty())
            {
                return azure_endpoint_;
            }
            const char separator = (azure_endpoint_.find('?') == std::string::npos) ? '?' : '&';
            return azure_endpoint_ + separator + "api-version=" + azure_api_version_;
        }

        std::string base = azure_endpoint_;
        if (!base.empty() && base.back() == '/')
        {
            base.pop_back();
        }
        std::ostringstream oss;
        oss << base << "/openai/deployments/" << azure_embedding_deployment_ << "/embeddings";
        if (!azure_api_version_.empty())
        {
            oss << "?api-version=" << azure_api_version_;
        }
        return oss.str();
    }

    std::string Config::azure_chat_url() const
    {
        auto append_version = [&](std::string url) -> std::string
        {
            const std::string &version = azure_chat_api_version_.empty() ? azure_api_version_ : azure_chat_api_version_;
            if (url.find("api-version=") != std::string::npos || version.empty())
            {
                return url;
            }
            const char separator = (url.find('?') == std::string::npos) ? '?' : '&';
            return url + separator + "api-version=" + version;
        };

        auto build_responses = [&](std::string base_url) -> std::string
        {
            if (azure_chat_deployment_.empty())
            {
                return "";
            }
            while (!base_url.empty() && base_url.back() == '/')
            {
                base_url.pop_back();
            }
            std::ostringstream oss;
            oss << base_url << "/openai/deployments/" << azure_chat_deployment_ << "/responses";
            return append_version(oss.str());
        };

        auto build_chat_completions = [&](std::string base_url) -> std::string
        {
            while (!base_url.empty() && base_url.back() == '/')
            {
                base_url.pop_back();
            }
            std::string url = base_url + "/chat/completions";
            if (base_url.find("/openai/v1") != std::string::npos)
            {
                return url;
            }
            return append_version(url);
        };

        auto normalize = [&](const std::string &value) -> std::string
        {
            if (value.empty())
            {
                return "";
            }
            if (value.find("responses") != std::string::npos)
            {
                return append_version(value);
            }
            if (value.find("chat/completions") != std::string::npos)
            {
                if (value.find("/openai/v1") != std::string::npos)
                {
                    return value;
                }
                return append_version(value);
            }
            if (value.find("openai/v1") != std::string::npos)
            {
                return build_chat_completions(value);
            }
            return build_responses(value);
        };

        if (!azure_chat_endpoint_override_.empty())
        {
            return normalize(azure_chat_endpoint_override_);
        }
        if (azure_endpoint_.empty())
        {
            return "";
        }
        return normalize(azure_endpoint_);
    }

} // namespace ragworker

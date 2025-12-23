#pragma once

#include <string>

namespace ragworker {

class Config {
public:
    static Config load();

    const std::string& environment() const noexcept { return environment_; }
    const std::string& pg_host() const noexcept { return pg_host_; }
    const std::string& pg_port() const noexcept { return pg_port_; }
    const std::string& pg_database() const noexcept { return pg_database_; }
    const std::string& pg_user() const noexcept { return pg_user_; }
    const std::string& pg_password() const noexcept { return pg_password_; }
    const std::string& azure_endpoint() const noexcept { return azure_endpoint_; }
    const std::string& azure_api_key() const noexcept { return azure_api_key_; }
    const std::string& azure_api_version() const noexcept { return azure_api_version_; }
    const std::string& azure_embedding_deployment() const noexcept { return azure_embedding_deployment_; }
    const std::string& azure_chat_deployment() const noexcept { return azure_chat_deployment_; }
    const std::string& azure_chat_endpoint_override() const noexcept { return azure_chat_endpoint_override_; }
    const std::string& azure_chat_api_version() const noexcept { return azure_chat_api_version_; }
    const std::string& kafka_brokers() const noexcept { return kafka_brokers_; }
    const std::string& kafka_worker_group() const noexcept { return kafka_worker_group_; }
    const std::string& qdrant_url() const noexcept { return qdrant_url_; }

    // Returns libpq-compatible connection information string.
    std::string pg_conninfo() const;
    std::string azure_embedding_url() const;
    std::string azure_chat_url() const;

private:
    Config(std::string environment,
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
           std::string qdrant_url);

    std::string environment_;
    std::string pg_host_;
    std::string pg_port_;
    std::string pg_database_;
    std::string pg_user_;
    std::string pg_password_;
    std::string azure_endpoint_;
    std::string azure_api_key_;
    std::string azure_api_version_;
    std::string azure_embedding_deployment_;
    std::string azure_chat_deployment_;
    std::string azure_chat_api_version_;
    std::string azure_chat_endpoint_override_;
    std::string kafka_brokers_;
    std::string kafka_worker_group_;
    std::string qdrant_url_;
};

}  // namespace ragworker

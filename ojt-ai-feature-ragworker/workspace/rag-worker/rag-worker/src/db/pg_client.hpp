#pragma once

#include <optional>
#include <string>
#include <vector>

#include <pqxx/pqxx>

#include "chunk/chunk.hpp"

namespace ragworker
{

    struct DocumentInfo
    {
        std::string id;
        std::string tenant_id;
        std::string kb_id;
        std::string status;
        int chunk_count = 0;
        std::string error_message;
    };

    class PostgresClient
    {
    public:
        explicit PostgresClient(const std::string &conninfo);

        bool mark_processing(const std::string &doc_id);
        bool is_ready(const std::string &doc_id);
        void mark_ready(const std::string &doc_id, int chunk_count);
        void mark_error(const std::string &doc_id, const std::string &message);
        void ensure_document_exists(const std::string &doc_id,
                                    const std::string &tenant_id,
                                    const std::string &kb_id);
        void upsert_chunks(const std::string &doc_id,
                           const std::string &tenant_id,
                           const std::string &kb_id,
                           const std::vector<Chunk> &chunks);

        std::optional<DocumentInfo> fetch_document(const std::string &doc_id);
        std::vector<Chunk> fetch_chunks(const std::string &doc_id);
        bool has_chunks(const std::string &doc_id);
        void reset_to_pending(const std::string &doc_id);

    private:
        pqxx::connection connection_;
    };

} // namespace ragworker

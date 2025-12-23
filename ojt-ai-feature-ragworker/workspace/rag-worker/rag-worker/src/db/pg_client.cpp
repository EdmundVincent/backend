#include "db/pg_client.hpp"

#include <stdexcept>

namespace ragworker {

namespace {
constexpr const char* kStatusPending = "PENDING";
constexpr const char* kStatusError = "ERROR";
constexpr const char* kStatusProcessing = "PROCESSING";
constexpr const char* kStatusReady = "READY";
}  // namespace

PostgresClient::PostgresClient(const std::string& conninfo)
    : connection_{conninfo} {
    if (!connection_.is_open()) {
        throw std::runtime_error("failed to open postgres connection");
    }
}

bool PostgresClient::mark_processing(const std::string& doc_id) {
    pqxx::work txn{connection_};
    const auto result = txn.exec_params(
        "UPDATE kb_document "
        "SET status = $2, error_message = NULL, updated_at = NOW() "
        "WHERE id = $1 AND status IN ($3, $4) "
        "RETURNING id;",
        doc_id,
        kStatusProcessing,
        kStatusPending,
        kStatusError);
    const bool updated = !result.empty();
    txn.commit();
    return updated;
}

bool PostgresClient::is_ready(const std::string& doc_id) {
    pqxx::read_transaction txn{connection_};
    const auto result =
        txn.exec_params("SELECT status FROM kb_document WHERE id = $1;", doc_id);
    txn.commit();
    if (result.empty()) {
        return false;
    }
    return result[0][0].as<std::string>() == kStatusReady;
}

void PostgresClient::mark_ready(const std::string& doc_id, int chunk_count) {
    pqxx::work txn{connection_};
    const auto result = txn.exec_params(
        "UPDATE kb_document "
        "SET status = $2, chunk_count = $3, error_message = NULL, updated_at = NOW() "
        "WHERE id = $1 AND status <> $2 "
        "RETURNING id;",
        doc_id,
        kStatusReady,
        chunk_count);
    (void)result;
    txn.commit();
}

void PostgresClient::mark_error(const std::string& doc_id, const std::string& message) {
    pqxx::work txn{connection_};
    txn.exec_params(
        "UPDATE kb_document "
        "SET status = $2, error_message = $3, updated_at = NOW() "
        "WHERE id = $1;",
        doc_id,
        kStatusError,
        message);
    txn.commit();
}

void PostgresClient::ensure_document_exists(const std::string& doc_id,
                                            const std::string& tenant_id,
                                            const std::string& kb_id) {
    pqxx::work txn{connection_};
    txn.exec_params(
        "INSERT INTO kb_document (id, tenant_id, kb_id, status, chunk_count, error_message) "
        "VALUES ($1, $2, $3, $4, 0, NULL) "
        "ON CONFLICT (id) DO NOTHING;",
        doc_id,
        tenant_id,
        kb_id,
        kStatusPending);
    txn.commit();
}

void PostgresClient::upsert_chunks(const std::string& doc_id,
                                   const std::string& tenant_id,
                                   const std::string& kb_id,
                                   const std::vector<Chunk>& chunks) {
    pqxx::work txn{connection_};
    for (const auto& chunk : chunks) {
        txn.exec_params(
            "INSERT INTO kb_chunk (doc_id, tenant_id, kb_id, seq_no, content, content_sha256, "
            "created_at) "
            "VALUES ($1, $2, $3, $4, $5, $6, NOW()) "
            "ON CONFLICT (doc_id, seq_no) DO NOTHING;",
            doc_id,
            tenant_id,
            kb_id,
            chunk.seq_no,
            chunk.content,
            chunk.content_sha256);
    }

    txn.exec_params(
        "UPDATE kb_document "
        "SET status = $2, chunk_count = $3, error_message = NULL, updated_at = NOW() "
        "WHERE id = $1;",
        doc_id,
        kStatusReady,
        static_cast<int>(chunks.size()));
    txn.commit();
}

std::optional<DocumentInfo> PostgresClient::fetch_document(const std::string& doc_id) {
    pqxx::read_transaction txn{connection_};
    const auto result = txn.exec_params(
        "SELECT id, tenant_id, kb_id, status, chunk_count, COALESCE(error_message, '') "
        "FROM kb_document "
        "WHERE id = $1;",
        doc_id);
    txn.commit();
    if (result.empty()) {
        return std::nullopt;
    }
    DocumentInfo info;
    info.id = result[0][0].c_str();
    info.tenant_id = result[0][1].c_str();
    info.kb_id = result[0][2].c_str();
    info.status = result[0][3].c_str();
    info.chunk_count = result[0][4].as<int>(0);
    info.error_message = result[0][5].c_str();
    return info;
}

std::vector<Chunk> PostgresClient::fetch_chunks(const std::string& doc_id) {
    pqxx::read_transaction txn{connection_};
    const auto result = txn.exec_params(
        "SELECT seq_no, content, content_sha256 "
        "FROM kb_chunk "
        "WHERE doc_id = $1 "
        "ORDER BY seq_no ASC;",
        doc_id);
    txn.commit();

    std::vector<Chunk> chunks;
    chunks.reserve(result.size());
    for (const auto& row : result) {
        Chunk chunk;
        chunk.seq_no = row[0].as<int>(0);
        chunk.content = row[1].c_str();
        chunk.content_sha256 = row[2].c_str();
        chunks.emplace_back(std::move(chunk));
    }
    return chunks;
}

bool PostgresClient::has_chunks(const std::string& doc_id) {
    pqxx::read_transaction txn{connection_};
    const auto result =
        txn.exec_params("SELECT 1 FROM kb_chunk WHERE doc_id = $1 LIMIT 1;", doc_id);
    txn.commit();
    return !result.empty();
}

void PostgresClient::reset_to_pending(const std::string& doc_id) {
    pqxx::work txn{connection_};
    txn.exec_params("DELETE FROM kb_chunk WHERE doc_id = $1;", doc_id);
    txn.exec_params(
        "UPDATE kb_document "
        "SET status = $2, chunk_count = 0, error_message = NULL, updated_at = NOW() "
        "WHERE id = $1;",
        doc_id,
        kStatusPending);
    txn.commit();
}

}  // namespace ragworker

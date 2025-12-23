#include "chunk/text_chunker.hpp"
#include "config/config.hpp"
#include "db/pg_client.hpp"
#include "embedding/azure_embedder.hpp"
#include "http/internal_server.hpp"
#include "mq/kafka_consumer.hpp"
#include "mq/kafka_producer.hpp"
#include "qdrant/qdrant_client.hpp"
#include "service/answer_service.hpp"
#include "service/search_service.hpp"
#include "storage/minio_client.hpp"
#include "util/log.hpp"
#include "util/time.hpp"
#include "worker/kafka_executor.hpp"
#include "version.hpp"

#include <exception>
#include <functional>
#include <iostream>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#include <nlohmann/json.hpp>

namespace ragworker {
namespace {

constexpr std::string_view kTestDocId = "00000000-0000-0000-0000-000000000001";
constexpr std::string_view kTestTenantId = "tenant-001";
constexpr std::string_view kTestKbId = "kb-001";
constexpr std::string_view kDemoKafkaBroker = "redpanda:9092";
constexpr std::string_view kDemoTopic = "doc_ingest";
constexpr std::string_view kConsumerGroupId = "rag-worker-dev";
constexpr std::string_view kMinioBucket = "rag-docs";
constexpr int kDefaultTopK = 5;
constexpr std::size_t kContentPreviewLength = 200;
std::string truncate_content(const std::string& content) {
    if (content.size() <= kContentPreviewLength) {
        return content;
    }
    if (kContentPreviewLength <= 3) {
        return content.substr(0, kContentPreviewLength);
    }
    return content.substr(0, kContentPreviewLength - 3) + "...";
}

void log_document_state(PostgresClient& client,
                        const std::string& doc_id,
                        std::string_view label) {
    auto info = client.fetch_document(doc_id);
    if (!info) {
        log::error(std::string{label} + ": document not found");
        return;
    }

    std::ostringstream oss;
    oss << label << ": doc_id=" << info->id << " status=" << info->status
        << " chunk_count=" << info->chunk_count;
    if (!info->error_message.empty()) {
        oss << " error=" << info->error_message;
    }
    log::info(oss.str());
}

int run_test_pg(const Config& config) {
    log::info("--test-pg starting");
    PostgresClient client(config.pg_conninfo());

    const std::string doc_id{kTestDocId};
    client.ensure_document_exists(doc_id, std::string{kTestTenantId}, std::string{kTestKbId});
    log_document_state(client, doc_id, "initial state");

    const bool moved_to_processing = client.mark_processing(doc_id);
    log::info(std::string{"mark_processing -> "} + (moved_to_processing ? "true" : "false"));
    if (!moved_to_processing) {
        log::info("mark_processing short-circuited (already PROCESSING or READY)");
    }
    log_document_state(client, doc_id, "after mark_processing");

    client.mark_ready(doc_id, 2);
    log::info("mark_ready applied with chunk_count=2");
    log_document_state(client, doc_id, "after mark_ready");

    const bool retry_processing = client.mark_processing(doc_id);
    log::info(std::string{"mark_processing (retry) -> "} + (retry_processing ? "true" : "false"));
    if (!retry_processing) {
        log::info("already READY, skip");
    }
    log_document_state(client, doc_id, "after retry mark_processing");

    const bool ready = client.is_ready(doc_id);
    log::info(std::string{"is_ready -> "} + (ready ? "true" : "false"));
    log_document_state(client, doc_id, "final state");

    log::info("--test-pg completed");
    return 0;
}

int run_produce_demo() {
    log::info("--produce-demo starting");
    KafkaProducer producer(std::string{kDemoKafkaBroker});

    nlohmann::json payload = {
        {"tenant_id", std::string{kTestTenantId}},
        {"kb_id", std::string{kTestKbId}},
        {"doc_id", std::string{kTestDocId}},
        {"object_key", "tenants/tenant-001/kb-001/doc-demo.txt"},
        {"content_type", "text/plain"},
        {"trace_id", "demo-trace-001"},
        {"requested_at", time::current_time_iso8601()},
    };

    const std::string message = payload.dump();
    producer.send(std::string{kDemoTopic}, message);
    log::info("demo message sent to topic doc_ingest");
    return 0;
}

std::string extract_required_string(const nlohmann::json& payload, const char* field) {
    if (!payload.contains(field)) {
        throw std::runtime_error(std::string{"missing field: "} + field);
    }
    if (!payload[field].is_string()) {
        throw std::runtime_error(std::string{"field not string: "} + field);
    }
    const std::string value = payload[field].get<std::string>();
    if (value.empty()) {
        throw std::runtime_error(std::string{"field empty: "} + field);
    }
    return value;
}

int run_consume_once(const Config& config) {
    log::info("--consume-once starting");
    KafkaConsumer consumer(std::string{kDemoKafkaBroker},
                           std::string{kConsumerGroupId},
                           {std::string{kDemoTopic}});
    auto message = consumer.poll(5000);
    if (!message) {
        log::info("no message received (timeout)");
        return 0;
    }

    const auto partition = message->partition();
    const auto offset = message->offset();
    bool success = true;

    const auto* payload_ptr = static_cast<const char*>(message->payload());
    const std::size_t payload_len = static_cast<std::size_t>(message->len());
    if (payload_len > 0 && payload_ptr == nullptr) {
        log::error("consume error: message payload missing");
        consumer.commit(*message);
        return 2;
    }

    std::string payload;
    if (payload_len > 0) {
        payload.assign(payload_ptr, payload_ptr + payload_len);
    }

    PostgresClient client(config.pg_conninfo());

    std::string tenant_id;
    std::string kb_id;
    std::string doc_id;
    std::string object_key;
    std::string trace_id;
    std::string content_type;
    bool should_process = false;

    try {
        auto json = nlohmann::json::parse(payload);

        tenant_id = extract_required_string(json, "tenant_id");
        kb_id = extract_required_string(json, "kb_id");
        doc_id = extract_required_string(json, "doc_id");
        object_key = extract_required_string(json, "object_key");
        trace_id = extract_required_string(json, "trace_id");
        content_type = extract_required_string(json, "content_type");

        std::ostringstream meta;
        meta << "message partition=" << partition << " offset=" << offset << " doc_id=" << doc_id
             << " tenant_id=" << tenant_id << " kb_id=" << kb_id << " trace_id=" << trace_id;
        log::info(meta.str());

        client.ensure_document_exists(doc_id, tenant_id, kb_id);
        auto doc = client.fetch_document(doc_id);
        if (!doc) {
            throw std::runtime_error("document missing after ensure_document_exists");
        }

        if (doc->status == "READY" && !client.has_chunks(doc_id)) {
            log::info("READY but chunk rows are missing; resetting to PENDING");
            client.reset_to_pending(doc_id);
            doc->status = "PENDING";
            doc->chunk_count = 0;
        }

        if (doc->status == "READY") {
            log::info("already READY, skip");
        } else if (doc->status == "PROCESSING") {
            log::info("already PROCESSING, skip");
        } else {
            should_process = client.mark_processing(doc_id);
            if (should_process) {
                log::info("mark_processing applied (PENDING/ERROR -> PROCESSING)");
            } else {
                log::info("already PROCESSING, skip");
            }
        }

        if (should_process) {
            if (content_type != "text/plain") {
                throw std::runtime_error("unsupported content_type: " + content_type);
            }

            MinioClient minio;
            const std::string text = minio.fetch_text(std::string{kMinioBucket}, object_key);
            const auto chunks = chunk_text(text);
            client.upsert_chunks(doc_id, tenant_id, kb_id, chunks);

            std::ostringstream result;
            result << "ingest completed, chunk_count=" << chunks.size();
            log::info(result.str());
        }
    } catch (const std::exception& ex) {
        success = false;
        log::error(std::string{"consume error: "} + ex.what());
        if (should_process && !doc_id.empty()) {
            client.mark_error(doc_id, ex.what());
            log::error("marked document as ERROR");
        } else {
            log::error("invalid message, committed offset");
        }
    }

    try {
        consumer.commit(*message);
        std::ostringstream oss;
        oss << "committed offset: " << partition << ':' << offset;
        log::info(oss.str());
    } catch (const std::exception& ex) {
        log::error(std::string{"commit failed: "} + ex.what());
        success = false;
    }

    return success ? 0 : 2;
}

int run_embed_doc(const Config& config, const std::string& doc_id) {
    log::info(std::string{"--embed-doc starting for "} + doc_id);
    try {
        PostgresClient client(config.pg_conninfo());
        auto doc = client.fetch_document(doc_id);
        if (!doc) {
            log::error("document not found");
            return 1;
        }
        if (doc->tenant_id.empty() || doc->kb_id.empty()) {
            log::error("document missing tenant/kb metadata");
            return 1;
        }
        if (doc->status != "READY") {
            log::error(std::string{"document status is "} + doc->status + ", expected READY");
            return 1;
        }

        auto chunks = client.fetch_chunks(doc_id);
        if (chunks.empty()) {
            log::error("no chunks found for document");
            return 1;
        }

        AzureEmbedder embedder(config);
        QdrantClient qdrant_client(config.qdrant_url());
        const std::string collection = doc->tenant_id + "__" + doc->kb_id;
        qdrant_client.ensure_collection(collection);

        int processed = 0;
        for (const auto& chunk : chunks) {
            const auto embedding = embedder.embed(chunk.content);
            nlohmann::json payload = {
                {"tenant_id", doc->tenant_id},
                {"kb_id", doc->kb_id},
                {"doc_id", doc->id},
                {"seq_no", chunk.seq_no},
                {"content", chunk.content},
            };
            const std::string point_key = doc->id + ":" + std::to_string(chunk.seq_no);
            const std::uint64_t point_id = std::hash<std::string>{}(point_key);
            qdrant_client.upsert_point(collection, point_id, embedding, payload);
            ++processed;
        }

        log::info("embedding + qdrant upsert completed, chunks=" + std::to_string(processed));
        return 0;
    } catch (const std::exception& ex) {
        log::error(std::string{"embedding failed: "} + ex.what());
        return 2;
    }
}

int run_search(const Config& config,
               const std::string& tenant_id,
               const std::string& kb_id,
               const std::string& query,
               int topk) {
    try {
        SearchService service(config);
        const auto response = service.search(tenant_id, kb_id, query, topk);

        std::cout << "collection=" << response.collection << " topk=" << response.topk << " query=\"" << query << "\"\n";
        int rank = 1;
        for (const auto& hit : response.results) {
            std::cout << "#" << rank++ << " score=" << hit.score << " doc_id=" << hit.doc_id
                      << " seq_no=" << hit.seq_no << "\n";
            std::cout << truncate_content(hit.content) << "\n";
        }
        if (response.results.empty()) {
            std::cout << "(no results)\n";
        }
        return 0;
    } catch (const std::exception& ex) {
        log::error(std::string{"search failed: "} + ex.what());
        return 2;
    }
}

int run_answer(const Config& config,
               const std::string& tenant_id,
               const std::string& kb_id,
               const std::string& question,
               int topk) {
    try {
        AnswerService service(config);
        const auto result = service.answer(tenant_id, kb_id, question, topk);

        std::cout << "Answer:\n"
                  << result.answer << "\n\nSources:\n";
        if (result.sources.empty()) {
            std::cout << "- (no sources)\n";
        } else {
            for (const auto& source : result.sources) {
                std::cout << "- doc_id=" << source.doc_id << " seq_no=" << source.seq_no
                          << " score=" << source.score << "\n";
            }
        }
        return 0;
    } catch (const std::exception& ex) {
        log::error(std::string{"answer failed: "} + ex.what());
        return 2;
    }
}

int run_serve(const Config& config) {
    return run_http_server(config, "0.0.0.0", 8080);
}

}  // namespace
}  // namespace ragworker

int main(int argc, char** argv) {
    try {
        ragworker::log::info(std::string{"rag-worker starting (version "} + ragworker::kVersion + ')');
        const auto config = ragworker::Config::load();

        bool test_pg = false;
        bool produce_demo = false;
        bool consume_once = false;
        std::string embed_doc_id;
        bool search_mode = false;
        std::string search_query;
        std::string answer_question;
        bool serve_mode = false;
        bool kafka_worker_mode = false;
        std::string tenant_id = "tenant-001";
        std::string kb_id = "kb-001";
        int topk = 5;
        for (int i = 1; i < argc; ++i) {
            if (std::string_view{argv[i]} == "--test-pg") {
                test_pg = true;
            } else if (std::string_view{argv[i]} == "--produce-demo") {
                produce_demo = true;
            } else if (std::string_view{argv[i]} == "--consume-once") {
                consume_once = true;
            } else if (std::string_view{argv[i]} == "--serve") {
                serve_mode = true;
            } else if (std::string_view{argv[i]} == "--kafka-worker") {
                kafka_worker_mode = true;
            } else if (std::string_view{argv[i]} == "--embed-doc") {
                if (i + 1 >= argc) {
                    ragworker::log::error("--embed-doc requires a document ID argument");
                    return 1;
                }
                embed_doc_id = argv[++i];
            } else if (std::string_view{argv[i]} == "--search") {
                if (i + 1 >= argc) {
                    ragworker::log::error("--search requires a query argument");
                    return 1;
                }
                search_mode = true;
                search_query = argv[++i];
            } else if (std::string_view{argv[i]} == "--answer") {
                if (i + 1 >= argc) {
                    ragworker::log::error("--answer requires a question argument");
                    return 1;
                }
                answer_question = argv[++i];
            } else if (std::string_view{argv[i]} == "--tenant") {
                if (i + 1 >= argc) {
                    ragworker::log::error("--tenant requires a value");
                    return 1;
                }
                tenant_id = argv[++i];
            } else if (std::string_view{argv[i]} == "--kb") {
                if (i + 1 >= argc) {
                    ragworker::log::error("--kb requires a value");
                    return 1;
                }
                kb_id = argv[++i];
            } else if (std::string_view{argv[i]} == "--topk") {
                if (i + 1 >= argc) {
                    ragworker::log::error("--topk requires a value");
                    return 1;
                }
                const std::string value = argv[++i];
                try {
                    topk = std::stoi(value);
                } catch (const std::exception&) {
                    ragworker::log::error("--topk requires an integer value");
                    return 1;
                }
            }
        }

        if (!answer_question.empty() && search_mode) {
            ragworker::log::error("cannot combine --answer with --search");
            return 1;
        }

        if (serve_mode && kafka_worker_mode) {
            ragworker::log::error("cannot combine --serve with --kafka-worker");
            return 1;
        }

        if (serve_mode) {
            return ragworker::run_serve(config);
        }

        if (kafka_worker_mode) {
            return ragworker::run_kafka_executor(config);
        }

        if (!answer_question.empty()) {
            return ragworker::run_answer(config, tenant_id, kb_id, answer_question, topk);
        }

        if (search_mode) {
            return ragworker::run_search(config, tenant_id, kb_id, search_query, topk);
        }

        if (produce_demo) {
            return ragworker::run_produce_demo();
        }

        if (!embed_doc_id.empty()) {
            return ragworker::run_embed_doc(config, embed_doc_id);
        }

        if (consume_once) {
            return ragworker::run_consume_once(config);
        }

        if (test_pg) {
            return ragworker::run_test_pg(config);
        }

        ragworker::log::info("rag-worker exiting (no pipeline yet)");
        return 0;
    } catch (const std::exception& ex) {
        ragworker::log::error(std::string{"fatal error: "} + ex.what());
        return 1;
    }
}

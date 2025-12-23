#include "worker/kafka_executor.hpp"

#include <chrono>
#include <memory>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>
#include <thread>

#include <nlohmann/json.hpp>

#include "mq/kafka_consumer.hpp"
#include "mq/kafka_producer.hpp"
#include "service/answer_service.hpp"
#include "service/search_service.hpp"
#include "util/log.hpp"

namespace ragworker {
namespace {

constexpr std::string_view kSearchRequestTopic = "rag_search_request";
constexpr std::string_view kAnswerRequestTopic = "rag_answer_request";
constexpr std::string_view kSearchResultTopic = "rag_search_result";
constexpr std::string_view kAnswerResultTopic = "rag_answer_result";
constexpr std::string_view kFailureTopic = "rag_failed";

constexpr int kPollTimeoutMs = 1000;
constexpr int kDefaultTopK = 5;
constexpr int kMaxRetries = 3;

enum class TaskType { Search, Answer };

struct RequestContext {
    TaskType type = TaskType::Search;
    std::string request_id;
    std::string trace_id;
    std::string tenant_id;
    std::string kb_id;
    std::string topic;
    int partition = 0;
    long long offset = 0;
};

std::string task_type_name(TaskType type) {
    return (type == TaskType::Search) ? "SEARCH" : "ANSWER";
}

std::string read_payload(const RdKafka::Message& message) {
    if (message.len() == 0 || message.payload() == nullptr) {
        return {};
    }
    const char* ptr = static_cast<const char*>(message.payload());
    return std::string(ptr, ptr + message.len());
}

nlohmann::json parse_json(const std::string& body) {
    try {
        return nlohmann::json::parse(body);
    } catch (const std::exception& ex) {
        throw std::runtime_error(std::string{"invalid JSON: "} + ex.what());
    }
}

template <typename T>
T require_field(const nlohmann::json& json, const char* field) {
    if (!json.contains(field)) {
        throw std::runtime_error(std::string{"missing field: "} + field);
    }
    try {
        return json.at(field).get<T>();
    } catch (const std::exception&) {
        throw std::runtime_error(std::string{"invalid field type: "} + field);
    }
}

int read_topk(const nlohmann::json& json) {
    if (!json.contains("topk")) {
        return kDefaultTopK;
    }
    if (!json["topk"].is_number_integer()) {
        throw std::runtime_error("invalid field type: topk");
    }
    const int topk = json["topk"].get<int>();
    if (topk <= 0) {
        throw std::runtime_error("topk must be positive");
    }
    return topk;
}

bool is_retryable(const std::string& message) {
    if (message.find("429") != std::string::npos || message.find("rate limit") != std::string::npos) {
        return true;
    }
    if (message.find("temporarily") != std::string::npos || message.find("timeout") != std::string::npos) {
        return true;
    }
    if (message.find("retry") != std::string::npos) {
        return true;
    }
    return false;
}

template <typename Fn>
auto execute_with_retry(Fn&& fn) -> decltype(fn()) {
    for (int attempt = 1; attempt <= kMaxRetries; ++attempt) {
        try {
            return fn();
        } catch (const std::exception& ex) {
            if (attempt == kMaxRetries || !is_retryable(ex.what())) {
                throw;
            }
            const auto backoff = std::chrono::milliseconds(500 * attempt);
            std::this_thread::sleep_for(backoff);
        }
    }
    throw std::runtime_error("retry attempts exhausted");
}

std::string classify_error_code(const std::exception& ex) {
    const std::string message = ex.what();
    if (message.rfind("invalid JSON", 0) == 0) {
        return "INVALID_JSON";
    }
    if (message.find("missing field") != std::string::npos ||
        message.find("invalid field type") != std::string::npos ||
        message.find("must be positive") != std::string::npos) {
        return "INVALID_REQUEST";
    }
    if (message.find("collection not found") != std::string::npos) {
        return "COLLECTION_NOT_FOUND";
    }
    if (message.find("qdrant") != std::string::npos) {
        return "QDRANT_ERROR";
    }
    if (message.find("azure") != std::string::npos) {
        if (message.find("401") != std::string::npos || message.find("403") != std::string::npos ||
            message.find("unauthorized") != std::string::npos) {
            return "AZURE_UNAUTHORIZED";
        }
        if (message.find("429") != std::string::npos || message.find("rate limit") != std::string::npos) {
            return "AZURE_RATE_LIMIT";
        }
        return "AZURE_ERROR";
    }
    return "INTERNAL_ERROR";
}

void produce_failure(KafkaProducer& producer,
                     const RequestContext& ctx,
                     const std::string& code,
                     const std::string& message) {
    nlohmann::json body;
    body["request_id"] = ctx.request_id;
    body["trace_id"] = ctx.trace_id;
    body["type"] = task_type_name(ctx.type);
    body["error"] = {{"code", code}, {"message", message}};
    producer.send(std::string{kFailureTopic}, body.dump());
}

void produce_search_result(KafkaProducer& producer,
                           const RequestContext& ctx,
                           const SearchResponse& response) {
    nlohmann::json body;
    body["request_id"] = ctx.request_id;
    body["trace_id"] = ctx.trace_id;
    body["status"] = "OK";
    body["results"] = nlohmann::json::array();
    int rank = 1;
    for (const auto& hit : response.results) {
        body["results"].push_back({
            {"rank", rank++},
            {"score", hit.score},
            {"doc_id", hit.doc_id},
            {"seq_no", hit.seq_no},
            {"content", hit.content},
        });
    }
    producer.send(std::string{kSearchResultTopic}, body.dump());
}

void produce_answer_result(KafkaProducer& producer,
                           const RequestContext& ctx,
                           const AnswerResponse& response) {
    nlohmann::json body;
    body["request_id"] = ctx.request_id;
    body["trace_id"] = ctx.trace_id;
    body["status"] = "OK";
    body["answer"] = response.answer;
    body["sources"] = nlohmann::json::array();
    for (const auto& source : response.sources) {
        body["sources"].push_back(
            {{"doc_id", source.doc_id}, {"seq_no", source.seq_no}, {"score", source.score}});
    }
    producer.send(std::string{kAnswerResultTopic}, body.dump());
}

void log_completion(const RequestContext& ctx, long latency_ms, bool success, const std::string& code) {
    std::ostringstream oss;
    oss << "kafka_worker type=" << task_type_name(ctx.type) << " request_id=" << ctx.request_id
        << " trace_id=" << ctx.trace_id << " topic=" << ctx.topic << " partition=" << ctx.partition
        << " offset=" << ctx.offset << " status=" << (success ? "OK" : "ERROR") << " code=" << code
        << " latency_ms=" << latency_ms;
    if (success) {
        log::info(oss.str());
    } else {
        log::error(oss.str());
    }
}

}  // namespace

int run_kafka_executor(const Config& config) {
    try {
        const std::vector<std::string> topics = {
            std::string{kSearchRequestTopic},
            std::string{kAnswerRequestTopic},
        };
        KafkaConsumer consumer(config.kafka_brokers(), config.kafka_worker_group(), topics);
        KafkaProducer producer(config.kafka_brokers());
        SearchService search_service(config);
        AnswerService answer_service(config);

        while (true) {
            auto message = consumer.poll(kPollTimeoutMs);
            if (!message) {
                continue;
            }

            const auto start = std::chrono::steady_clock::now();
            RequestContext ctx;
            ctx.topic = message->topic_name();
            ctx.partition = message->partition();
            ctx.offset = message->offset();

            if (ctx.topic == kSearchRequestTopic) {
                ctx.type = TaskType::Search;
            } else if (ctx.topic == kAnswerRequestTopic) {
                ctx.type = TaskType::Answer;
            } else {
                log::error("kafka_worker received message from unexpected topic: " + ctx.topic);
                try {
                    consumer.commit(*message);
                } catch (const std::exception& ex) {
                    log::error(std::string{"commit failed: "} + ex.what());
                }
                continue;
            }

            bool success = false;
            std::string code = "OK";

            try {
                const std::string payload = read_payload(*message);
                const auto json = parse_json(payload);
                ctx.request_id = require_field<std::string>(json, "request_id");
                ctx.trace_id = require_field<std::string>(json, "trace_id");
                ctx.tenant_id = require_field<std::string>(json, "tenant_id");
                ctx.kb_id = require_field<std::string>(json, "kb_id");
                const int topk = read_topk(json);

                if (ctx.type == TaskType::Search) {
                    const std::string query = require_field<std::string>(json, "query");
                    const auto response = execute_with_retry([&]() {
                        return search_service.search(ctx.tenant_id, ctx.kb_id, query, topk);
                    });
                    produce_search_result(producer, ctx, response);
                } else {
                    const std::string question = require_field<std::string>(json, "question");
                    const auto response = execute_with_retry([&]() {
                        return answer_service.answer(ctx.tenant_id, ctx.kb_id, question, topk);
                    });
                    produce_answer_result(producer, ctx, response);
                }
                success = true;
            } catch (const std::exception& ex) {
                code = classify_error_code(ex);
                produce_failure(producer, ctx, code, ex.what());
            }

            try {
                consumer.commit(*message);
            } catch (const std::exception& ex) {
                log::error(std::string{"commit failed: "} + ex.what());
            }

            const auto latency_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
            log_completion(ctx, latency_ms, success, code);
        }
    } catch (const std::exception& ex) {
        log::error(std::string{"kafka executor failed: "} + ex.what());
        return 2;
    }
}

}  // namespace ragworker

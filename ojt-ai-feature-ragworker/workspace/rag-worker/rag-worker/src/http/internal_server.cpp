#include "http/internal_server.hpp"

#include <chrono>
#include <sstream>
#include <string>
#include <string_view>

#include <httplib.h>
#include <nlohmann/json.hpp>

#include "service/answer_service.hpp"
#include "service/search_service.hpp"
#include "util/log.hpp"
#include "util/uuid.hpp"

namespace ragworker
{
    namespace
    {

        constexpr std::string_view kJson = "application/json";

        struct HttpError
        {
            int status = 500;
            std::string code = "INTERNAL_ERROR";
            std::string message = "internal server error";
        };

        HttpError classify_exception(const std::exception &ex)
        {
            const std::string message = ex.what();
            HttpError error;
            error.message = message;

            if (message.find("qdrant collection not found") != std::string::npos)
            {
                error.status = 404;
                error.code = "COLLECTION_NOT_FOUND";
                return error;
            }

            if (message.find("qdrant") != std::string::npos)
            {
                error.status = 502;
                error.code = "QDRANT_ERROR";
                return error;
            }

            if (message.find("azure") != std::string::npos)
            {
                if (message.find("429") != std::string::npos || message.find("rate limit") != std::string::npos)
                {
                    error.status = 503;
                    error.code = "AZURE_RATE_LIMIT";
                }
                else
                {
                    error.status = 502;
                    error.code = "AZURE_ERROR";
                }
                return error;
            }

            return error;
        }

        void set_error_response(httplib::Response &res, const HttpError &error)
        {
            nlohmann::json body;
            body["error"] = {{"code", error.code}, {"message", error.message}};
            res.status = error.status;
            res.set_content(body.dump(), std::string{kJson});
        }

        void set_success(httplib::Response &res, const nlohmann::json &json)
        {
            res.status = 200;
            res.set_content(json.dump(), std::string{kJson});
        }

        nlohmann::json parse_json_or_throw(const std::string &body)
        {
            try
            {
                return nlohmann::json::parse(body);
            }
            catch (const std::exception &ex)
            {
                throw std::runtime_error(std::string{"invalid JSON: "} + ex.what());
            }
        }

        template <typename T>
        T require_field(const nlohmann::json &json, const char *field)
        {
            if (!json.contains(field))
            {
                throw std::runtime_error(std::string{"missing field: "} + field);
            }
            try
            {
                return json.at(field).get<T>();
            }
            catch (const std::exception &)
            {
                throw std::runtime_error(std::string{"invalid field type: "} + field);
            }
        }

        int extract_topk(const nlohmann::json &json, int default_value)
        {
            if (!json.contains("topk"))
            {
                return default_value;
            }
            if (!json["topk"].is_number_integer())
            {
                throw std::runtime_error("invalid field type: topk");
            }
            return json["topk"].get<int>();
        }

        void log_request(const std::string &action,
                         const std::string &trace_id,
                         const std::string &tenant_id,
                         const std::string &kb_id,
                         long latency_ms,
                         int status)
        {
            std::ostringstream oss;
            oss << action << " trace_id=" << trace_id << " tenant_id=" << tenant_id << " kb_id=" << kb_id
                << " status=" << status << " latency_ms=" << latency_ms;
            log::info(oss.str());
        }

    } // namespace

    int run_http_server(const Config &config, const std::string &host, int port)
    {
        SearchService search_service(config);
        AnswerService answer_service(config);

        httplib::Server server;

        server.Post("/internal/search", [&](const httplib::Request &req, httplib::Response &res)
                    {
        const auto trace_id = uuid::generate();
        const auto start = std::chrono::steady_clock::now();
        std::string tenant_id;
        std::string kb_id;
        bool handled = false;

        nlohmann::json json;
        try {
            json = parse_json_or_throw(req.body);
            tenant_id = require_field<std::string>(json, "tenant_id");
            kb_id = require_field<std::string>(json, "kb_id");
        } catch (const std::exception& ex) {
            handled = true;
            set_error_response(res, HttpError{400, "INVALID_REQUEST", ex.what()});
            log::error("search trace_id=" + trace_id + " invalid request: " + ex.what());
        }

        if (!handled) {
            try {
                const std::string query = require_field<std::string>(json, "query");
                const int topk = extract_topk(json, 5);
                const auto response = search_service.search(tenant_id, kb_id, query, topk);

                nlohmann::json body;
                body["collection"] = response.collection;
                body["topk"] = response.topk;
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

                set_success(res, body);
            } catch (const std::exception& ex) {
                handled = true;
                auto error = classify_exception(ex);
                set_error_response(res, error);
                log::error("search trace_id=" + trace_id + " failed: " + ex.what());
            }
        }

        const auto latency_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        log_request("search_http", trace_id, tenant_id, kb_id, latency_ms, res.status); });

        server.Post("/internal/answer", [&](const httplib::Request &req, httplib::Response &res)
                    {
        const auto trace_id = uuid::generate();
        const auto start = std::chrono::steady_clock::now();
        std::string tenant_id;
        std::string kb_id;
        bool handled = false;

        nlohmann::json json;
        try {
            json = parse_json_or_throw(req.body);
            tenant_id = require_field<std::string>(json, "tenant_id");
            kb_id = require_field<std::string>(json, "kb_id");
        } catch (const std::exception& ex) {
            handled = true;
            set_error_response(res, HttpError{400, "INVALID_REQUEST", ex.what()});
            log::error("answer trace_id=" + trace_id + " invalid request: " + ex.what());
        }

        if (!handled) {
            try {
                const std::string question = require_field<std::string>(json, "question");
                const int topk = extract_topk(json, 5);
                const auto response = answer_service.answer(tenant_id, kb_id, question, topk);

                nlohmann::json body;
                body["answer"] = response.answer;
                body["sources"] = nlohmann::json::array();
                for (const auto& source : response.sources) {
                    body["sources"].push_back(
                        {{"doc_id", source.doc_id}, {"seq_no", source.seq_no}, {"score", source.score}});
                }

                set_success(res, body);
            } catch (const std::exception& ex) {
                handled = true;
                auto error = classify_exception(ex);
                set_error_response(res, error);
                log::error("answer trace_id=" + trace_id + " failed: " + ex.what());
            }
        }

        const auto latency_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        log_request("answer_http", trace_id, tenant_id, kb_id, latency_ms, res.status); });

        server.set_error_handler([](const httplib::Request &, httplib::Response &res)
                                 {
        HttpError error;
        set_error_response(res, error); });

        log::info("http server listening on " + host + ":" + std::to_string(port));
        if (!server.listen(host.c_str(), port))
        {
            log::error("http server failed to start");
            return 2;
        }
        return 0;
    }

} // namespace ragworker

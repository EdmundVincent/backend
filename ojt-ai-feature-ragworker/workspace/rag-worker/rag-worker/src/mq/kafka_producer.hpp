#pragma once

#include <memory>
#include <string>

#include <librdkafka/rdkafkacpp.h>

namespace ragworker {

class KafkaProducer {
public:
    explicit KafkaProducer(const std::string& brokers);

    // Sends the given message to topic, blocking until delivery succeeds or fails.
    void send(const std::string& topic, const std::string& message);

private:
    std::unique_ptr<RdKafka::Producer> producer_;
};

}  // namespace ragworker

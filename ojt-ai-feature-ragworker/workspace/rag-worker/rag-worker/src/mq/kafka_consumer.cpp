#include "mq/kafka_consumer.hpp"

#include <stdexcept>

namespace ragworker {

namespace {

class AssignRebalanceCb final : public RdKafka::RebalanceCb {
public:
    void rebalance_cb(RdKafka::KafkaConsumer* consumer,
                      RdKafka::ErrorCode err,
                      std::vector<RdKafka::TopicPartition*>& partitions) override {
        if (err == RdKafka::ERR__ASSIGN_PARTITIONS) {
            consumer->assign(partitions);
        } else {
            consumer->unassign();
        }
    }
};

std::unique_ptr<RdKafka::Conf> make_conf() {
    std::unique_ptr<RdKafka::Conf> conf(RdKafka::Conf::create(RdKafka::Conf::CONF_GLOBAL));
    if (!conf) {
        throw std::runtime_error("failed to allocate kafka conf");
    }
    return conf;
}

}  // namespace

KafkaConsumer::KafkaConsumer(const std::string& brokers,
                             const std::string& group_id,
                             const std::vector<std::string>& topics) {
    std::string errstr;
    auto conf = make_conf();
    if (conf->set("bootstrap.servers", brokers, errstr) != RdKafka::Conf::CONF_OK) {
        throw std::runtime_error("failed to set bootstrap.servers: " + errstr);
    }
    if (conf->set("group.id", group_id, errstr) != RdKafka::Conf::CONF_OK) {
        throw std::runtime_error("failed to set group.id: " + errstr);
    }
    if (conf->set("enable.auto.commit", "false", errstr) != RdKafka::Conf::CONF_OK) {
        throw std::runtime_error("failed to disable auto commit: " + errstr);
    }
    if (conf->set("auto.offset.reset", "earliest", errstr) != RdKafka::Conf::CONF_OK) {
        throw std::runtime_error("failed to set auto.offset.reset: " + errstr);
    }
    rebalance_cb_ = std::make_unique<AssignRebalanceCb>();
    if (conf->set("rebalance_cb", rebalance_cb_.get(), errstr) != RdKafka::Conf::CONF_OK) {
        throw std::runtime_error("failed to set rebalance_cb: " + errstr);
    }

    consumer_.reset(RdKafka::KafkaConsumer::create(conf.get(), errstr));
    if (!consumer_) {
        throw std::runtime_error("failed to create kafka consumer: " + errstr);
    }

    RdKafka::ErrorCode subscribe_err = consumer_->subscribe(topics);
    if (subscribe_err != RdKafka::ERR_NO_ERROR) {
        throw std::runtime_error("failed to subscribe: " + RdKafka::err2str(subscribe_err));
    }
}

KafkaConsumer::~KafkaConsumer() {
    if (consumer_) {
        consumer_->close();
    }
}

std::unique_ptr<RdKafka::Message> KafkaConsumer::poll(int timeout_ms) {
    std::unique_ptr<RdKafka::Message> message(consumer_->consume(timeout_ms));
    if (!message) {
        return nullptr;
    }

    switch (message->err()) {
        case RdKafka::ERR_NO_ERROR:
            return message;
        case RdKafka::ERR__TIMED_OUT:
        case RdKafka::ERR__PARTITION_EOF:
            return nullptr;
        default:
            throw std::runtime_error("kafka consume error: " + message->errstr());
    }
}

void KafkaConsumer::commit(const RdKafka::Message& message) {
    const auto err = consumer_->commitSync(const_cast<RdKafka::Message*>(&message));
    if (err != RdKafka::ERR_NO_ERROR) {
        throw std::runtime_error("failed to commit offset: " + RdKafka::err2str(err));
    }
}

}  // namespace ragworker

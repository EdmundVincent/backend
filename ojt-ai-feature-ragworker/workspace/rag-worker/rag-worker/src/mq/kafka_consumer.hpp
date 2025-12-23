#pragma once

#include <memory>
#include <string>
#include <vector>

#include <librdkafka/rdkafkacpp.h>

namespace ragworker
{

    class KafkaConsumer
    {
    public:
        KafkaConsumer(const std::string &brokers,
                      const std::string &group_id,
                      const std::vector<std::string> &topics);
        ~KafkaConsumer();

        // 返回消费到的消息，timeout_ms为等待时间，单位毫秒， 或者空指针表示超时或无消息
        std::unique_ptr<RdKafka::Message> poll(int timeout_ms);
        void commit(const RdKafka::Message &message);

    private:
        std::unique_ptr<RdKafka::KafkaConsumer> consumer_;
        std::unique_ptr<RdKafka::RebalanceCb> rebalance_cb_;
    };

} // namespace ragworker

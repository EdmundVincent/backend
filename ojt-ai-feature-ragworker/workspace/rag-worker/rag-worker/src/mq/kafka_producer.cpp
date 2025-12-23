#include "mq/kafka_producer.hpp"

#include <memory>
#include <stdexcept>
#include <string>

namespace ragworker
{

    namespace
    {

        std::unique_ptr<RdKafka::Conf> make_conf()
        {
            return std::unique_ptr<RdKafka::Conf>(RdKafka::Conf::create(RdKafka::Conf::CONF_GLOBAL));
        }

        std::unique_ptr<RdKafka::Topic> make_topic(RdKafka::Producer *producer,
                                                   const std::string &topic_name)
        {
            std::string errstr;
            std::unique_ptr<RdKafka::Topic> topic(
                RdKafka::Topic::create(producer, topic_name, nullptr, errstr));
            if (!topic)
            {
                throw std::runtime_error("カフカ話題を作成失敗: " + errstr);
            }
            return topic;
        }

    } // namespace

    KafkaProducer::KafkaProducer(const std::string &brokers)
    {
        std::string errstr;
        auto conf = make_conf();
        if (!conf)
        {
            throw std::runtime_error("カフカ設定を作成失敗");
        }
        if (conf->set("bootstrap.servers", brokers, errstr) != RdKafka::Conf::CONF_OK)
        {
            throw std::runtime_error("boostrap失敗: " + errstr);
        }
        if (conf->set("enable.idempotence", "false", errstr) != RdKafka::Conf::CONF_OK)
        {
            throw std::runtime_error("Idempotence設定失敗: " + errstr);
        }

        producer_.reset(RdKafka::Producer::create(conf.get(), errstr));
        if (!producer_)
        {
            throw std::runtime_error("カフカ生産者作成失敗: " + errstr);
        }
    }

    void KafkaProducer::send(const std::string &topic_name, const std::string &message)
    {
        auto topic = make_topic(producer_.get(), topic_name);
        const auto error = producer_->produce(topic.get(),
                                              RdKafka::Topic::PARTITION_UA,
                                              RdKafka::Producer::RK_MSG_COPY,
                                              const_cast<char *>(message.data()),
                                              message.size(),
                                              nullptr,
                                              nullptr);
        if (error != RdKafka::ERR_NO_ERROR)
        {
            throw std::runtime_error("failed to produce message: " + RdKafka::err2str(error));
        }

        const auto flush_error = producer_->flush(5000);
        if (flush_error != RdKafka::ERR_NO_ERROR)
        {
            throw std::runtime_error("kafka flush failed: " + RdKafka::err2str(flush_error));
        }
    }

} // namespace ragworker

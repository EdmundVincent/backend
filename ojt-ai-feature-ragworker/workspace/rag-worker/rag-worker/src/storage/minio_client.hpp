#pragma once

#include <memory>
#include <string>

namespace ragworker {

class MinioClient {
public:
    MinioClient();
    ~MinioClient();

    std::string fetch_text(const std::string& bucket, const std::string& object_key);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace ragworker

#include "storage/minio_client.hpp"

#ifdef NO_AWSSDK
#include <stdexcept>

namespace ragworker {

// Dummy implementation when AWS SDK is not available
struct MinioClient::Impl {};

MinioClient::MinioClient() = default;
MinioClient::~MinioClient() = default;

std::string MinioClient::fetch_text(const std::string& bucket, const std::string& object_key) {
    throw std::runtime_error("MinIO/AWSSDK disabled at build time");
}

}  // namespace ragworker

#else
#include "storage/minio_client.hpp"

#include <cstdlib>
#include <memory>
#include <sstream>
#include <stdexcept>

#include <aws/core/Aws.h>
#include <aws/core/auth/AWSCredentials.h>
#include <aws/s3/S3Client.h>
#include <aws/s3/model/GetObjectRequest.h>

namespace ragworker {
namespace {

class AwsRuntime {
public:
    AwsRuntime() { Aws::InitAPI(options_); }
    ~AwsRuntime() { Aws::ShutdownAPI(options_); }

    AwsRuntime(const AwsRuntime&) = delete;
    AwsRuntime& operator=(const AwsRuntime&) = delete;

private:
    Aws::SDKOptions options_;
};

AwsRuntime& aws_runtime() {
    static AwsRuntime runtime;
    return runtime;
}

std::string env_or_throw(const char* name) {
    if (const char* value = std::getenv(name); value && *value) {
        return value;
    }
    throw std::runtime_error(std::string{"missing environment variable: "} + name);
}

Aws::Client::ClientConfiguration make_client_config() {
    Aws::Client::ClientConfiguration config;
    config.scheme = Aws::Http::Scheme::HTTP;
    config.endpointOverride = "http://minio:9000";
    config.verifySSL = false;
    config.region = "us-east-1";
    config.useDualStack = false;
    return config;
}

}  // namespace

struct MinioClient::Impl {
    explicit Impl()
        : credentials(env_or_throw("MINIO_ROOT_USER"), env_or_throw("MINIO_ROOT_PASSWORD")),
          client(credentials,
                 make_client_config(),
                 Aws::Client::AWSAuthV4Signer::PayloadSigningPolicy::Never,
                 false) {}

    Aws::Auth::AWSCredentials credentials;
    Aws::S3::S3Client client;
};

MinioClient::MinioClient() {
    (void)aws_runtime();
    impl_ = std::make_unique<Impl>();
}

MinioClient::~MinioClient() = default;

std::string MinioClient::fetch_text(const std::string& bucket, const std::string& object_key) {
    Aws::S3::Model::GetObjectRequest request;
    request.SetBucket(bucket.c_str());
    request.SetKey(object_key.c_str());

    auto outcome = impl_->client.GetObject(request);
    if (!outcome.IsSuccess()) {
        const auto& error = outcome.GetError();
        throw std::runtime_error("minio get_object failed: " + error.GetMessage());
    }

    auto result = outcome.GetResultWithOwnership();
    std::ostringstream oss;
    oss << result.GetBody().rdbuf();
    return oss.str();
}

}  // namespace ragworker

#endif
#pragma once

#include <string>
#include <vector>

namespace ragworker
{

    class Config;

    class AzureEmbedder
    {
    public:
        explicit AzureEmbedder(const Config &config);

        std::vector<float> embed(const std::string &text) const;

    private:
        std::string url_;
        std::string api_key_;
        int dimension_;
    };

} // namespace ragworker

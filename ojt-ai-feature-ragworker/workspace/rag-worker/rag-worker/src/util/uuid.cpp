#include "util/uuid.hpp"

#include <array>
#include <random>

namespace ragworker::uuid {
namespace {

std::mt19937& rng() {
    thread_local std::mt19937 gen{std::random_device{}()};
    return gen;
}

}  // namespace

std::string generate() {
    static constexpr std::array<char, 16> kHex = {'0', '1', '2', '3', '4', '5', '6', '7',
                                                  '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    std::uniform_int_distribution<int> dist(0, 15);

    std::string out(36, '0');
    for (int i = 0; i < 36; ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            out[i] = '-';
            continue;
        }
        out[i] = kHex[dist(rng())];
    }
    return out;
}

}  // namespace ragworker::uuid

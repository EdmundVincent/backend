cmake_minimum_required(VERSION 3.16)

project(rag-worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src")
set(SOURCES
    "${SRC_DIR}/main.cpp"
    "${SRC_DIR}/chunk/text_chunker.cpp"
    "${SRC_DIR}/config/config.cpp"
    "${SRC_DIR}/db/pg_client.cpp"
    "${SRC_DIR}/embedding/azure_embedder.cpp"
    "${SRC_DIR}/embedding/azure_chat_client.cpp"
    "${SRC_DIR}/http/internal_server.cpp"
    "${SRC_DIR}/mq/kafka_consumer.cpp"
    "${SRC_DIR}/mq/kafka_producer.cpp"
    "${SRC_DIR}/net/http_client.cpp"
    "${SRC_DIR}/qdrant/qdrant_client.cpp"
    "${SRC_DIR}/service/search_service.cpp"
    "${SRC_DIR}/service/answer_service.cpp"
    "${SRC_DIR}/worker/kafka_executor.cpp"
    "${SRC_DIR}/storage/minio_client.cpp"
    "${SRC_DIR}/util/log.cpp"
    "${SRC_DIR}/util/time.cpp"
    "${SRC_DIR}/util/uuid.cpp"
)

list(APPEND CMAKE_PREFIX_PATH "/opt/vcpkg/installed/x64-linux/share")
list(APPEND CMAKE_PREFIX_PATH "/opt/vcpkg/installed/x64-linux/share/awssdk")
set(ENV{PKG_CONFIG_PATH} "/opt/vcpkg/installed/x64-linux/lib/pkgconfig:/opt/vcpkg/installed/x64-linux/share/pkgconfig:$ENV{PKG_CONFIG_PATH}")
set(OPENSSL_ROOT_DIR "/opt/vcpkg/installed/x64-linux")

find_package(PkgConfig REQUIRED)
pkg_check_modules(PQXX REQUIRED IMPORTED_TARGET libpqxx)
pkg_check_modules(RDKAFKA REQUIRED IMPORTED_TARGET rdkafka++)
find_package(nlohmann_json 3.2.0 REQUIRED)
find_package(AWSSDK QUIET COMPONENTS s3)
find_package(OpenSSL REQUIRED COMPONENTS SSL Crypto)
find_package(CURL REQUIRED)

# If AWSSDK is missing, build without S3 client and define NO_AWSSDK
if (NOT AWSSDK_FOUND AND NOT DEFINED AWSSDK_LINK_LIBRARIES)
    message(WARNING "AWSSDK not found; building without S3/MinIO via AWS SDK")
    add_definitions(-DNO_AWSSDK)
endif()

set(VCPKG_LIB_DIR "/opt/vcpkg/installed/x64-linux/lib")
link_directories(${VCPKG_LIB_DIR})
set(CMAKE_BUILD_RPATH "${CMAKE_BUILD_RPATH};${VCPKG_LIB_DIR}")
set(CMAKE_INSTALL_RPATH "${CMAKE_INSTALL_RPATH};${VCPKG_LIB_DIR}")

add_executable(rag-worker-dev ${SOURCES})

target_include_directories(rag-worker-dev
    PRIVATE
        "${SRC_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}/third_party"
)

target_link_libraries(rag-worker-dev
    PRIVATE
        PkgConfig::PQXX
        PkgConfig::RDKAFKA
        nlohmann_json::nlohmann_json
        OpenSSL::SSL
        OpenSSL::Crypto
        CURL::libcurl
)
# Link AWSSDK only if available
if (AWSSDK_FOUND OR DEFINED AWSSDK_LINK_LIBRARIES)
    target_link_libraries(rag-worker-dev PRIVATE ${AWSSDK_LINK_LIBRARIES})
endif()

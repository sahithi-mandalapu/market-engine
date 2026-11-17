cmake_minimum_required(VERSION 3.16)
project(matching_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
add_compile_options(-Wall -Wextra)

find_package(Threads REQUIRED)

add_library(me_core src/orderbook.cpp src/engine.cpp src/protocol.cpp)
target_include_directories(me_core PUBLIC include)
target_link_libraries(me_core PUBLIC Threads::Threads)

add_executable(me_server src/server.cpp)
target_link_libraries(me_server me_core)

add_executable(me_bench bench/bench.cpp)
target_link_libraries(me_bench me_core)

option(ME_BUILD_TESTS "build the unit tests (downloads googletest)" ON)
if(ME_BUILD_TESTS)
  include(FetchContent)
  FetchContent_Declare(googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG v1.14.0
    GIT_SHALLOW TRUE)
  set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
  FetchContent_MakeAvailable(googletest)

  enable_testing()
  add_executable(me_tests tests/test_orderbook.cpp tests/test_protocol.cpp tests/test_engine.cpp)
  target_link_libraries(me_tests me_core GTest::gtest_main)
  include(GoogleTest)
  gtest_discover_tests(me_tests)
endif()

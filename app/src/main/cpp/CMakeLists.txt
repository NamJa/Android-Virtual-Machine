cmake_minimum_required(VERSION 3.22.1)

project(avm_host LANGUAGES CXX)

add_library(avm_host SHARED vm_native_bridge.cpp)

find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(log-lib log)

target_link_libraries(
    avm_host
    ${android-lib}
    ${aaudio-lib}
    ${log-lib}
)

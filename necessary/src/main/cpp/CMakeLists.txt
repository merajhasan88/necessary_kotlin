cmake_minimum_required(VERSION 3.22.1)
project(necessary)

# The native code comparison (code_integrity.cpp), loaded as libnecessary.so.
add_library(necessary SHARED code_integrity.cpp)

find_library(log-lib log)
target_link_libraries(necessary ${log-lib})

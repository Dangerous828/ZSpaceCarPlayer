cmake_minimum_required(VERSION 3.10.2)

project("zspacecarplayer_dsp")

add_library(
        zspacecarplayer_dsp
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        zspacecarplayer_dsp
        ${log-lib}
)

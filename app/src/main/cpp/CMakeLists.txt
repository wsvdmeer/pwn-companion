cmake_minimum_required(VERSION 3.22.1)
project(wpacrack C)

add_library(wpacrack SHARED wpa_crack.c)

# -O3 for the tight PBKDF2 loop.
target_compile_options(wpacrack PRIVATE -O3 -funroll-loops)

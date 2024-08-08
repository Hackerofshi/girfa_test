
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.10.2)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("grafika")


# 指定需要使用的头文件
INCLUDE_DIRECTORIES(${PROJECT_SOURCE_DIR}/src/main/jniLibs/include)
# 指定外部链接库目录
LINK_DIRECTORIES(${PROJECT_SOURCE_DIR}/src/main/jniLibs/armeabi-v7a)

# 添加要打包的资源
FILE(GLOB SRC_LISTS "${PROJECT_SOURCE_DIR}/src/main/cpp/*.cpp" "${PROJECT_SOURCE_DIR}/src/main/cpp/*.c")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(grafika-lib SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        src/main/cpp/grafika.cpp)



# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(grafika-lib
        # List libraries link to the target library
        # other libs
        yuv
        # Android libs
        android
        log)

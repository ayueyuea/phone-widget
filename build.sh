#!/bin/bash
# 简单的构建脚本，用于在没有gradle wrapper的情况下构建项目

echo "检查Java环境..."
java -version

echo "检查Android SDK环境..."
if [ -z "$ANDROID_HOME" ]; then
    echo "错误: ANDROID_HOME未设置"
    exit 1
fi

echo "尝试使用本地gradle构建..."
if command -v gradle &> /dev/null; then
    gradle build
else
    echo "错误: gradle未安装，请安装gradle或使用Android Studio构建"
    echo "可以从 https://gradle.org/install/ 下载gradle"
    exit 1
fi
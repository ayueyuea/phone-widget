@echo off
REM 简单的构建脚本，用于在没有gradle wrapper的情况下构建项目

echo 检查Java环境...
java -version

echo 检查Android SDK环境...
if "%ANDROID_HOME%"=="" (
    echo 错误: ANDROID_HOME未设置
    exit /b 1
)

echo 尝试使用本地gradle构建...
where gradle >nul 2>&1
if %ERRORLEVEL% equ 0 (
    gradle build
) else (
    echo 错误: gradle未安装，请安装gradle或使用Android Studio构建
    echo 可以从 https://gradle.org/install/ 下载gradle
    exit /b 1
)
# 手机状态 Widget

一个Android桌面小部件，显示电池温度、充电功率、快充协议等信息。

## 功能特性

- 📱 实时显示电池温度（带颜色指示）
- ⚡ 显示充电功率和快充协议（支持小米澎湃秒充、Turbo快充等）
- 🔋 显示电池电量和健康状态
- 🔌 自动检测充电状态变化
- 📊 支持多种数据源（sysfs、BatteryManager API、广播Intent）
- 🎨 简洁美观的UI设计

## 支持设备

- 所有Android 8.0+设备（基础功能）
- 小米/红米/澎湃OS设备（支持快充协议检测）
- 其他支持sysfs接口的设备

## 构建说明

### 方法1：使用Android Studio
1. 使用Android Studio打开项目
2. 等待Gradle同步完成
3. 点击运行按钮构建并安装到设备

### 方法2：使用命令行
```bash
# 方法2.1：使用gradle wrapper（如果已下载gradle-wrapper.jar）
./gradlew assembleDebug

# 方法2.2：使用本地gradle
gradle assembleDebug

# 方法2.3：使用提供的构建脚本（Windows）
build.bat

# 方法2.4：使用提供的构建脚本（Linux/Mac）
chmod +x build.sh
./build.sh
```

### 安装
构建完成后，APK文件位于：
```
app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
phone-widget/
├── app/
│   ├── src/main/java/com/phonewidget/
│   │   ├── PhoneWidgetProvider.kt      # Widget主逻辑
│   │   ├── BatteryDataProvider.kt      # 电池数据获取
│   │   └── XiaomiChargerReader.kt      # 小米快充协议读取
│   ├── src/main/res/                   # 资源文件
│   └── build.gradle.kts                # 模块构建配置
├── build.gradle.kts                    # 项目构建配置
├── settings.gradle.kts                 # 项目设置
├── gradle.properties                   # Gradle属性
└── gradlew / gradlew.bat               # Gradle wrapper脚本
```

## 技术实现

### 数据获取优先级
1. **sysfs接口**（小米设备）：读取`/sys/class/power_supply/`下的文件，获取最精确的快充数据
2. **BatteryManager API**（Android 5.0+）：使用标准API获取电池信息
3. **广播Intent**（兼容模式）：通过`ACTION_BATTERY_CHANGED`广播获取数据

### 快充协议检测
- 小米澎湃秒充（≥55W）
- 小米Turbo快充（≥30W）
- QC/PD快充（≥18W）
- 普通充电（5-18W）
- 涓流充电（<5W）

## 权限说明

本项目不需要任何危险权限：
- ❌ 不需要`BATTERY_STATS`权限（从Android 5.0开始，BatteryManager API不需要此权限）
- ❌ 不需要网络权限
- ❌ 不需要存储权限
- ✅ 仅需要标准Widget权限

## 优化特性

- ✅ 代码优化：使用Kotlin协程友好的设计
- ✅ 性能优化：批量读取sysfs文件，减少IO操作
- ✅ 错误处理：完善的异常捕获和降级机制
- ✅ 内存优化：避免内存泄漏，及时释放资源
- ✅ 兼容性：支持Android 8.0+，考虑广播限制

## 问题排查

### 常见问题
1. **Widget不更新**：从Android 8.0开始，`ACTION_BATTERY_CHANGED`广播受限，Widget主要依赖点击刷新和定期更新
2. **快充协议不显示**：非小米设备或设备不支持sysfs接口
3. **温度显示不准确**：不同设备温度传感器精度不同

### 调试模式
如需调试，可以：
1. 取消注释代码中的`Timber.d()`日志语句
2. 添加`implementation("com.jakewharton.timber:timber:5.0.1")`依赖
3. 在Application中初始化Timber

## 许可证

MIT License
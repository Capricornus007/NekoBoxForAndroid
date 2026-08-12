# 修复 CI Workflow 中的 Gradle Daemon 残留问题

## 问题
在 CI workflow 中运行 gradle 相关命令时，daemon 进程没有正确停止，导致：
1. 可能占用不必要的系统资源
2. 影响后续的构建任务
3. 可能导致缓存问题

## 修复方案
在每个 gradle 命令后添加 `./gradlew --stop` 来停止 daemon 进程。

## 修改位置
1. **test 作业** - Unit Tests 步骤
   - 命令: `./gradlew app:testOssDebugUnitTest`
   - 修复: 添加 `./gradlew --stop`

2. **lint 作业** - Lint 步骤
   - 命令: `./gradlew app:lintOssDebug`
   - 修复: 添加 `./gradlew --stop`

3. **lint 作业** - Spotless 步骤
   - 命令: `./gradlew spotlessCheck`
   - 修复: 添加 `./gradlew --stop`

4. **build 作业** - Gradle Build 步骤
   - 命令: `./gradlew app:assembleOssRelease`
   - 修复: 添加 `./gradlew --stop`

## 影响
- ✅ 防止 daemon 进程残留
- ✅ 减少资源占用
- ✅ 提高构建稳定性
- ✅ 避免潜在的缓存冲突

## 测试
提交后将通过 GitHub Actions CI 流程自动测试。

# VS Code Java 开发环境配置

## 已安装插件

### 1. Extension Pack for Java (Microsoft)
- 包含 Java 语言支持、Maven 支持、测试运行器等
- 提供智能代码补全、代码跳转、代码重构等功能

### 2. Spring Boot Extension Pack (VMware)
- Spring Boot Tools：Spring Boot 项目支持
- Spring Initializr：快速创建 Spring Boot 项目
- Spring Dashboard：管理和监控 Spring Boot 应用

## 配置步骤

### 1. 配置 Java 环境
确保已安装 JDK 17，并设置环境变量：
```
JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-17.0.x.x
PATH = %JAVA_HOME%\bin
```

### 2. 打开项目
1. 在 VS Code 中点击「文件」→「打开文件夹」
2. 选择 `d:/projects/06-bmw/interview-prep-project`
3. VS Code 会自动识别 Maven 项目

### 3. 等待依赖下载
首次打开时，VS Code 会自动：
- 扫描项目结构
- 下载 Maven 依赖
- 构建项目索引

可以在底部「终端」查看进度。

### 4. 验证配置成功
打开 `pom.xml`，应该能看到：
- 语法高亮
- 代码补全
- 依赖版本显示

## 常用快捷键

| 快捷键 | 功能 |
|--------|------|
| `Ctrl+Shift+O` | 打开大纲视图 |
| `F12` | 跳转到定义 |
| `Ctrl+点击` | 跳转到定义 |
| `Alt+Shift+F` | 格式化代码 |
| `Ctrl+Shift+F` | 全局搜索 |
| `Ctrl+Shift+P` | 命令面板 |

## 常见问题

**Q: 代码显示红线错误？**
A: 等待依赖下载完成，或按 `Ctrl+Shift+P` 输入 "Reload Window"

**Q: JDK 版本不对？**
A: 按 `Ctrl+Shift+P`，输入 "Configure Java Runtime"，选择 JDK 17
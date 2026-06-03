# Java 环境变量配置

## 问题
Java 已安装在 `D:\Program Files\Java\jdk-25.0.3`，但命令行找不到 java 命令。

## 解决方案

### 设置 JAVA_HOME

1. **打开环境变量设置**
   - 右键点击「此电脑」→「属性」
   - 点击左侧「高级系统设置」
   - 点击下方「环境变量」

2. **在「系统变量」中新建/编辑**
   ```
   变量名: JAVA_HOME
   变量值: D:\Program Files\Java\jdk-25.0.3
   ```

3. **编辑 PATH**
   - 找到「系统变量」中的 `Path`
   - 点击「编辑」→「新建」
   - 添加：`%JAVA_HOME%\bin`

4. **验证**
   - **重新打开一个新的 PowerShell 窗口**
   - 执行：`java -version`

## 预期输出
```
java version "25.0.3" 2025-06-18 LTS
Java(TM) SE Runtime Environment (build 25.0.3+10-b652)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.3+10-b652, mixed mode, sharing)
```

## 注意
- JDK 25 可以使用，兼容本工程需要的 Java 17+
- 不需要再安装 JDK 17

---

## 安装 Maven

### 方式A：手动下载（推荐，因为 winget 可能失败）

1. 访问 https://maven.apache.org/download.cgi
2. 下载 `apache-maven-3.9.9-bin.zip`（或最新版）
3. 解压到 `C:\apache-maven`
4. 设置环境变量：
   - `MAVEN_HOME` = `C:\apache-maven`
   - PATH 添加：`C:\apache-maven\bin`

### 方式B：使用 winget（可能失败）
```powershell
winget install Apache.Maven
```

验证安装：
```powershell
mvn -version
```

预期输出：
```
Apache Maven 3.9.x
Maven home: ...
```

---

## 配置 Maven 阿里云镜像（加速下载）

1. 找到 Maven 配置文件 `settings.xml`
   - Windows 通常在：`C:\Users\你的用户名\.m2\settings.xml`
   - 或 Maven 安装目录下：`C:\apache-maven\conf\settings.xml`

2. 添加镜像配置：
```xml
<mirrors>
  <mirror>
    <id>aliyun</id>
    <mirrorOf>central</mirrorOf>
    <name>阿里云公共仓库</name>
    <url>https://maven.aliyun.com/repository/central</url>
  </mirror>
</mirrors>
```

---

## 最后验证

重新打开 PowerShell，执行：
```powershell
java -version
mvn -version
docker --version
```

全部通过后，进入工程目录开始学习：
```powershell
cd d:/projects/06-bmw/interview-prep-project
mvn clean compile
```

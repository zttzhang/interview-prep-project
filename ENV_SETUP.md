# 环境安装指南

## 当前环境状态

| 工具 | 状态 |
|------|------|
| Docker | ✅ 已安装 (v29.4.0) |
| Java | ❌ 需要安装 |
| Maven | ❌ 需要安装 |

---

## 安装步骤

### 1. 安装 JDK 17

**方式A：使用 Winget（推荐，最简单）**
打开 PowerShell 或 CMD，执行：
```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```

**方式B：手动下载**
1. 访问 https://adoptium.net/temurin/releases/?version=17
2. 下载 Windows x64 JDK 17 (.msi)
3. 运行安装程序，一路下一步即可

---

### 2. 安装 Maven 3.9+

**使用 Winget：**
```powershell
winget install Apache.Maven
```

**手动安装：**
1. 访问 https://maven.apache.org/download.cgi
2. 下载 `apache-maven-3.9.x-bin.zip`
3. 解压到 `C:\apache-maven`

---

### 3. 设置环境变量

#### Java 环境变量
```
变量名: JAVA_HOME
变量值: C:\Program Files\Eclipse Adoptium\jdk-17.0.x.x
```

#### Maven 环境变量
```
变量名: MAVEN_HOME
变量值: C:\apache-maven
```

#### 更新 PATH
添加以下两个路径：
```
%JAVA_HOME%\bin
%MAVEN_HOME%\bin
```

**设置方法：**
1. 右键「此电脑」→ 属性
2. 点击「高级系统设置」
3. 点击「环境变量」
4. 在「系统变量」中新建/编辑上述变量

---

### 4. 验证安装

**重新打开一个新的命令行窗口**，执行：
```powershell
java -version
mvn -version
```

**预期输出：**
```
openjdk 17.x.x
Apache Maven 3.9.x
```

---

### 5. 启动工程

```powershell
# 进入工程目录
cd d:/projects/06-bmw/interview-prep-project

# 编译项目
mvn clean compile

# 启动中间件
docker-compose up -d

# 查看服务状态
docker-compose ps
```

---

## 常见问题

**Q: winget 命令找不到？**
A: Windows 10/11 默认已安装 winget，如果找不到，尝试更新系统或手动下载安装

**Q: 安装后还是提示找不到 java？**
A: 确保环境变量设置正确，并且**重新打开命令行窗口**

**Q: Maven 下载依赖很慢？**
A: 可以配置阿里云镜像，在 Maven 的 `settings.xml` 中添加：
```xml
<mirrors>
  <mirror>
    <id>aliyun</id>
    <mirrorOf>central</mirrorOf>
    <name>阿里云公共仓库</name>
    <url>https://maven.aliyun.com/repository/central</url>
  </mirror>
</mirrors>
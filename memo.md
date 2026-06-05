# mvn install -pl common -am -DskipTests

常用 Maven 命令行选项的缩写对照：

短选项 完整写法 含义
-pl --projects 指定要构建的子模块
-am --also-make 同时构建指定模块的上游依赖
-amd --also-make-dependents 同时构建依赖指定模块的下游模块
-rf --resume-from 从某个模块断点续建
-T --threads 多线程并行构建，如 -T 4
-U --update-snapshots 强制更新 SNAPSHOT 依赖
-e --errors 显示完整错误堆栈
-X --debug 开启 debug 日志

# mvn test 命令

##

## mvn test -pl module-mybatis -Dtest=SqlInjectionTest#testSharpBraces

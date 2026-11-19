# ============================================================
# Data Khaos - 通用 Dockerfile（多阶段构建）
#
# 单个 Dockerfile 服务于全部 11 个可运行服务，通过构建参数
# MODULE 指定打包哪个模块。构建阶段一次性编译整个 Maven
# reactor（各服务共享该层缓存），运行阶段只拷贝对应 jar。
#
# 用法：
#   docker build --build-arg MODULE=data-khaos-gateway -t data-khaos/gateway .
#   （docker-compose.yml 已内置该构建参数，无需手动执行）
# ============================================================

# ---- 构建阶段：编译整个 reactor ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先拷贝 pom 与全部模块源码，一次 mvn package 产出所有 jar
COPY pom.xml ./
COPY data-khaos-common/       ./data-khaos-common/
COPY data-khaos-auth-api/     ./data-khaos-auth-api/
COPY data-khaos-permission-api/ ./data-khaos-permission-api/
COPY data-khaos-datasource-api/ ./data-khaos-datasource-api/
COPY data-khaos-mart-api/     ./data-khaos-mart-api/
COPY data-khaos-dquality-api/ ./data-khaos-dquality-api/
COPY data-khaos-pipeline-api/   ./data-khaos-pipeline-api/
COPY data-khaos-workflow-api/   ./data-khaos-workflow-api/
COPY data-khaos-gateway/      ./data-khaos-gateway/
COPY data-khaos-auth/         ./data-khaos-auth/
COPY data-khaos-permission/   ./data-khaos-permission/
COPY data-khaos-approval/     ./data-khaos-approval/
COPY data-khaos-datasource/   ./data-khaos-datasource/
COPY data-khaos-metadata/     ./data-khaos-metadata/
COPY data-khaos-mart/         ./data-khaos-mart/
COPY data-khaos-query/        ./data-khaos-query/
COPY data-khaos-visual/       ./data-khaos-visual/
COPY data-khaos-schedule/     ./data-khaos-schedule/
COPY data-khaos-notification/ ./data-khaos-notification/
COPY data-khaos-dquality/     ./data-khaos-dquality/
COPY data-khaos-pipeline/     ./data-khaos-pipeline/
COPY data-khaos-workflow/     ./data-khaos-workflow/

RUN mvn -q -B -DskipTests clean package

# 阿里云 DataX 官方发行包（数据同步引擎，仅 pipeline 需要）
# 一个轻量 stage，仅用于提供 DataX 发行包与 arm64 JDK8 的构建上下文。
FROM apache/hive:4.0.0 AS datax_home_stage

# ---- 运行阶段：按 MODULE 取对应 jar ----
FROM eclipse-temurin:17-jre-jammy AS runtime_stage
ARG MODULE

ENV JAVA_OPTS="-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai" \
    TZ=Asia/Shanghai \
    DATAX_HOME=/opt/datax

WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app/app.jar

# DataX 运行环境（仅 pipeline 需要）：python3 + 官方发行包解压到 /opt/datax，并让 `python` 指向 python3
# 官方根路径 datax.tar.gz 已限 ACL，改用带版本的 202309 路径，失败时回退华为云镜像。
# 注意：DataX 官方仅支持 JDK8。JDK17 下 hdfsreader 序列化 HDFS 对象会 StackOverflow，
#       因此额外安装 Temurin 8 作为 DataX 专用 JAVA，供 datax.py 调用。
RUN if [ "${MODULE}" = "data-khaos-pipeline" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends python3 wget ca-certificates \
      && ln -sf /usr/bin/python3 /usr/local/bin/python \
      && cd /opt \
      && ( wget -q -O datax.tar.gz https://datax-opensource.oss-cn-hangzhou.aliyuncs.com/202309/datax.tar.gz \
           || wget -q -O datax.tar.gz https://mirrors.huaweicloud.com/datax/datax.tar.gz ) \
      && tar -zxf datax.tar.gz \
      && rm -f datax.tar.gz \
      && chmod +x /opt/datax/bin/datax.py \
      && rm -rf /var/lib/apt/lists/*; \
    fi

# DataX 专用 JDK8（仅 pipeline 需要）：官方 datax.py 默认用系统 java（JDK17），
# 需替换为 JDK8 才能正常跑 hdfsreader。直接从 apache/hive 镜像复用 arm64 JDK8
# （apt/openjdk-jre 默认是 amd64，arm64 宿主机无法运行），并把 datax.py 的 java 指向它。
COPY --from=datax_home_stage /usr/local/openjdk-8 /opt/jdk8
RUN if [ "${MODULE}" = "data-khaos-pipeline" ]; then \
      sed -i 's|ENGINE_COMMAND = "java -server|ENGINE_COMMAND = "/opt/jdk8/bin/java -server|' /opt/datax/bin/datax.py \
      && rm -rf /var/lib/apt/lists/*; \
    fi

# 工作流编排需要执行 Python 脚本节点，运行时镜像补充 python3（仅 workflow 需要）
RUN if [ "${MODULE}" = "data-khaos-workflow" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends python3 bash \
      && ln -sf /usr/bin/python3 /usr/local/bin/python \
      && rm -rf /var/lib/apt/lists/*; \
    fi

# 所有服务默认监听 8080 容器端口，主机端口由 compose 映射
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

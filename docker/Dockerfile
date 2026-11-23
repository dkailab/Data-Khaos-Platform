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

# --mount=type=cache 把 /root/.m2/repository 持久化到 BuildKit 缓存：
#   源码变更导致本层重跑时，依赖不再重复下载（首次仍会拉取并写入缓存）。
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -q -B -DskipTests clean package

# ---- DataX 工具阶段（独立于构建阶段）----
# 把体积大、耗时长的 DataX 发行包下载/解压单独放一个不依赖 maven 构建的阶段：
# 源码变更只会击渍 build/runtime 层，本阶段命中缓存后不再重复下载。
# 发行包下载写入 BuildKit 缓存挂载（id=dk-datax），跨构建复用，避免每次重建都重新下载。
# 下载失败不阻断构建：DataX 仅 DATAX 引擎需要，MySQL 基线使用 DB_SYNC 引擎。
FROM eclipse-temurin:17-jre-jammy AS datax_tools_stage
RUN apt-get update \
      && apt-get install -y --no-install-recommends wget ca-certificates \
      && rm -rf /var/lib/apt/lists/*
RUN --mount=type=cache,target=/opt/dk-datax-cache,id=dk-datax \
    set -eu; \
    mkdir -p /opt/datax; \
    if [ ! -s /opt/dk-datax-cache/datax.tar.gz ]; then \
      wget -q --timeout=90 --tries=2 -O /opt/dk-datax-cache/datax.tar.gz.part https://datax-opensource.oss-cn-hangzhou.aliyuncs.com/202309/datax.tar.gz \
        || wget -q --timeout=90 --tries=2 -O /opt/dk-datax-cache/datax.tar.gz.part https://mirrors.huaweicloud.com/datax/datax.tar.gz \
        || rm -f /opt/dk-datax-cache/datax.tar.gz.part; \
    fi; \
    if [ -s /opt/dk-datax-cache/datax.tar.gz.part ]; then \
      mv /opt/dk-datax-cache/datax.tar.gz.part /opt/dk-datax-cache/datax.tar.gz; \
    fi; \
    if [ -s /opt/dk-datax-cache/datax.tar.gz ]; then \
      tar -zxf /opt/dk-datax-cache/datax.tar.gz -C /opt \
        && chmod +x /opt/datax/bin/datax.py; \
    else \
      echo "DataX 发行包下载失败，镜像不含 DataX（DATAX 引擎不可用，DB_SYNC 不受影响）" > /opt/datax/DISABLED; \
    fi

# DataX 官方仅支持 JDK8（JDK17 下 hdfsreader 序列化 HDFS 对象会 StackOverflow），
# 因此单独提供 JDK8 供 datax.py 调用。用 temurin:8-jre 而不是 apache/hive，
# 体积从约 1.4GB 降到约 50MB。
FROM eclipse-temurin:8-jre-jammy AS datax_home_stage

# ---- 运行阶段：按 MODULE 取对应 jar ----
FROM eclipse-temurin:17-jre-jammy AS runtime_stage
ARG MODULE

ENV JAVA_OPTS="-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai" \
    TZ=Asia/Shanghai \
    DATAX_HOME=/opt/datax

WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app/app.jar

# DataX 运行环境（仅 pipeline 需要，见下方 pipeline_stage）：python3 + 已解压的 /opt/datax。
# datax.py 默认用系统 java（JDK17），需替换为 JDK8 才能正常跑 hdfsreader。

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

# ---- pipeline 专用运行阶段（默认，不含 DataX）----
# DataX 是可选引擎：发行包约 1GB 且来自公网镜像，默认构建不下载，避免拖慢日常构建。
# 需要 DATAX 引擎时用 docker/docker-compose.datax.yml（target: pipeline_datax）单独构建。
# 未安装 DataX 时 DataXEngine 的 isAvailable() 返回 false，任务侧给出明确提示，不影响 DB_SYNC。
FROM runtime_stage AS pipeline_stage

# ---- pipeline + DataX 阶段（可选，仅 pipeline_datax 目标引用上面两个 stage）----
# BuildKit 只构建目标可达的 stage，因此默认构建不会触发下面的下载。
FROM pipeline_stage AS pipeline_datax
COPY --from=datax_tools_stage /opt/datax /opt/datax
COPY --from=datax_home_stage /opt/java/openjdk /opt/jdk8
RUN apt-get update \
      && apt-get install -y --no-install-recommends python3 \
      && ln -sf /usr/bin/python3 /usr/local/bin/python \
      && if [ ! -f /opt/datax/DISABLED ]; then \
           sed -i 's|ENGINE_COMMAND = "java -server|ENGINE_COMMAND = "/opt/jdk8/bin/java -server|' /opt/datax/bin/datax.py; \
         fi \
      && rm -rf /var/lib/apt/lists/*

# ---- 默认构建目标：通用运行阶段（未指定 target 时产出，不含 DataX）----
FROM runtime_stage AS final

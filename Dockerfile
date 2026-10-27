# 知造 SIRP · device-hub —— 多阶段构建
# 构建：docker build -t knowfab-sirp .
# 国内加速：docker build --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public -t knowfab-sirp .

# ---------- 前端层：Node 22 构建 Vue（ADR-002） ----------
FROM node:22-alpine AS frontend
ARG NPM_REGISTRY=""
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json ./
RUN if [ -n "$NPM_REGISTRY" ]; then npm config set registry "$NPM_REGISTRY"; fi && npm ci
COPY frontend/ ./
# vite.config 默认 outDir 指向 ../src/main/resources/static/app，容器内显式重定向
RUN npm run build -- --outDir /fe/dist --emptyOutDir

# ---------- 构建层：Maven + JDK 17 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# 可选国内 Maven 镜像（deploy-cn.sh 会传入阿里云公共仓）
ARG MAVEN_MIRROR=""
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 \
      && printf '<settings><mirrors><mirror><id>cn</id><mirrorOf>central</mirrorOf><url>%s</url><mirrorBlocked>false</mirrorBlocked></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; fi
# 先只拷 pom 拉依赖：源码不变时这层直接走缓存
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
# 前端产物并入 jar（static/app）
COPY --from=frontend /fe/dist ./src/main/resources/static/app
RUN mvn -q -B -DskipTests package

# ---------- 运行层：纯 JRE，体积最小 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/device-hub.jar /app/device-hub.jar
ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
EXPOSE 8080
# data/ 存本地附件与首次生成的管理员密码文件；logs/ 存 stdout
VOLUME ["/app/data", "/app/logs"]
ENTRYPOINT ["java", "-Xms256m", "-Xmx1024m", "-jar", "/app/device-hub.jar", "--server.port=8080"]

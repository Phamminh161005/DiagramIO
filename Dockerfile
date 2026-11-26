# ---- Giai đoạn 1: build ra file jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml trước để Docker cache phần tải thư viện; sửa code không phải tải lại
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Test chạy riêng bằng "./mvnw test" (cần Docker cho Testcontainers), nên ở đây không biên dịch cả mã test
RUN mvn -B -q package -Dmaven.test.skip=true

# ---- Giai đoạn 2: tách jar theo lớp (thư viện, loader, mã của app) ----
# Mỗi lớp là một layer riêng của image: sửa code chỉ đổi layer "application" (vài trăm KB), các layer thư viện
# (~80 MB) được dùng lại khi build, push và pull
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- Giai đoạn 3: image chạy, chỉ có JRE + các lớp của app ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Không chạy app bằng root
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=layers /app/extracted/dependencies/ ./
COPY --from=layers /app/extracted/spring-boot-loader/ ./
COPY --from=layers /app/extracted/snapshot-dependencies/ ./
COPY --from=layers /app/extracted/application/ ./

# Heap theo RAM của container (mặc định của JVM chỉ là 25%, phần còn lại bỏ phí trong container chỉ chạy một JVM);
# hết bộ nhớ thì thoát hẳn để Docker khởi động lại (restart: unless-stopped) thay vì chạy tiếp ở trạng thái hỏng.
# Ghi đè bằng biến môi trường JAVA_TOOL_OPTIONS khi chạy.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

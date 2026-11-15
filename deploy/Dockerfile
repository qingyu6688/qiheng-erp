ARG JRE_IMAGE
FROM ${JRE_IMAGE}
WORKDIR /opt/erp
# 以交付包解压根目录为构建上下文，基础镜像须为带 shell 的固定摘要 JRE 21。
RUN mkdir -p /var/log/erp && chown 10001:10001 /var/log/erp
COPY erp-server.jar /opt/erp/erp-server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/opt/erp/erp-server.jar"]

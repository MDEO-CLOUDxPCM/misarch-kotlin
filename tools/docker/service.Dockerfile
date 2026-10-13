# Image of one service: layered Spring Boot jar on top of misarch-kotlin/jre-base:dev (built by tools/image.sh).
FROM misarch-kotlin/jre-base:dev AS extract
WORKDIR /x
COPY app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination out

FROM misarch-kotlin/jre-base:dev
COPY --from=extract /x/out/dependencies/ ./
COPY --from=extract /x/out/spring-boot-loader/ ./
COPY --from=extract /x/out/snapshot-dependencies/ ./
COPY --from=extract /x/out/application/ ./

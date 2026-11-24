FROM gradle:9.4.1-jdk25 AS build
WORKDIR /app
COPY --chown=gradle:gradle settings.gradle build.gradle ./
COPY --chown=gradle:gradle src ./src
RUN gradle --no-daemon build

FROM itzg/minecraft-server:java25
COPY --from=build /app/build/libs/RPG-Lobby.jar /plugins/RPG-Lobby.jar
COPY deployment/bukkit.yml /config/bukkit.yml

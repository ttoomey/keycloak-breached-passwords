# Build/test container: JDK 21 + Maven. Keycloak itself runs from the
# upstream image in docker-compose.yml.
FROM maven:3.9-eclipse-temurin-21

# Dev utilities for working inside the container. The Docker CLI comes from
# Docker's apt repo and talks to the host daemon through the mounted socket.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
        > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 \
        python-is-python3 \
        jq \
        less \
        vim \
        docker-ce-cli \
        docker-buildx-plugin \
        docker-compose-plugin \
        ripgrep \
        fd-find \
        unzip \
        tree \
        xmlstarlet \
        procps \
    && ln -s /usr/bin/fdfind /usr/local/bin/fd \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

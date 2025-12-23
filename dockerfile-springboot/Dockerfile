FROM azul/zulu-openjdk-debian:17

# --- ユーザー作成 ---
# Dev Container用のvscodeユーザーを作成 (UID/GID 1000)し、パスワードなしでsudoできるように設定
RUN apt-get update && apt-get install -y sudo && \
    groupadd -g 1000 vscode && \
    useradd -u 1000 -g 1000 -ms /bin/bash vscode && \
    echo "vscode ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# --- 環境変数とツールのバージョン定義 ---
ARG MAVEN_VERSION=3.9.3
ARG NODE_VERSION=20.x
ENV MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION}
ENV PATH=$MAVEN_HOME/bin:$PATH
RUN rm -f /etc/apt/sources.list.d/zulu.list || true && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
    wget git curl build-essential gnupg libpq-dev unzip dialog apt-utils && \
    # Node.jsをインストール
    curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION} | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    # ★★★ yarnをインストール ★★★
    npm install -g yarn && \
    # Mavenをインストール
    wget https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz -P /tmp && \
    tar -xzvf /tmp/apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt && \
    # 不要なファイルを削除
    rm /tmp/apache-maven-${MAVEN_VERSION}-bin.tar.gz && \
    rm -rf /var/lib/apt/lists/*
# 5. タイムゾーンの設定 (JST: 日本標準時)
# ログ出力やDB操作の日時を日本時間に統一し、トラブルシューティングを容易にします
ENV TZ=Asia/Tokyo
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 6. ビルド成果物の配置
# ビルドステージ(Stage 1)で生成されたJARファイルのみをコピーします
USER vscode

# pom.xml のあるディレクトリに移動
WORKDIR /home/vscode/workspace

# Spring Boot アプリケーション起動コマンド
# docker-compose で指定されるコマンドで実行される
CMD [ "bash" ]
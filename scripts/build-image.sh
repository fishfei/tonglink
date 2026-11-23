#!/usr/bin/env bash
# 在本地构建轻量模式 Docker 镜像 tar（无需 Docker daemon）。
# 产物：dist/tonglink-studio-lite.tar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"

# mvn.cmd 是 Windows 批处理，JAVA_HOME 必须是 Windows 风格路径，
# 否则它会回退到系统自带 JDK（编码不对会把中文注释编译成乱码）。
WIN_ROOT="$(cygpath -w "$ROOT" 2>/dev/null || echo "$ROOT" | sed 's|^/\([a-z]\)/|\U\1:\\|')"
# 强制使用随仓库提供的 JDK 21，不能用环境里可能存在的旧 JDK（否则中文注释会按 GBK 编译成乱码）。
export JAVA_HOME="$WIN_ROOT\\tools\\jdk-21.0.2"
MVN="${MVN:-$TOOLS/apache-maven-3.9.9/bin/mvn.cmd}"
MVN_ARGS=(-B -ntp -s "$TOOLS/settings.xml" -f "$ROOT/backend/pom.xml")

# 镜像内运行所需 Java / Node 二进制不入库，缺失时自动下载
if [ ! -x "$ROOT/docker/extra/opt/java/bin/java" ]; then
  echo "==> 下载 Java 21 Linux 运行时"
  mkdir -p "$ROOT/docker/extra/opt/java" "$ROOT/tools"
  curl -fsSL -o "$ROOT/tools/jdk21-linux.tar.gz" \
    https://repo.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_linux-x64_bin.tar.gz
  tar -xzf "$ROOT/tools/jdk21-linux.tar.gz" -C "$ROOT/docker/extra/opt/java" --strip-components=1 --exclude='legal/*' || true
fi
if [ ! -x "$ROOT/docker/extra/opt/node/bin/node" ]; then
  echo "==> 下载 Node.js 22 Linux 运行时"
  mkdir -p "$ROOT/docker/extra/opt/node" "$ROOT/tools"
  curl -fsSL -o "$ROOT/tools/node-linux.tar.xz" \
    https://registry.npmmirror.com/-/binary/node/v22.14.0/node-v22.14.0-linux-x64.tar.xz
  python3 - "$ROOT/tools/node-linux.tar.xz" "$ROOT/docker/extra/opt/node" <<'PY'
import sys, tarfile
src, dst = sys.argv[1], sys.argv[2]
with tarfile.open(src, 'r:xz') as tf:
    members = []
    for m in tf.getmembers():
        if '/' not in m.name:
            continue
        m.name = m.name.split('/', 1)[1]
        members.append(m)
    tf.extractall(dst, members=members)
PY
fi

echo "==> 构建前端静态资源"
npm --prefix "$ROOT/frontend" run build
# 先清空旧产物，避免历次构建的 hash chunk 残留在镜像里
STATIC_DIR="$ROOT/backend/tonglink-app/src/main/resources/static"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -r "$ROOT/frontend/dist/." "$STATIC_DIR/"

echo "==> 构建后端（install 会把各子模块装进本地仓库，供后面 Jib 单独打包 app 时解析依赖）"
"$MVN" "${MVN_ARGS[@]}" install -DskipTests

echo "==> 构建 OCI 镜像 tar"
"$MVN" "${MVN_ARGS[@]}" -pl tonglink-app jib:buildTar

echo "==> 完成：$ROOT/dist/tonglink-studio-lite.tar"

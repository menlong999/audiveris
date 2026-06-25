ARG BASE_NODE_GLIBC_IMAGE=node:20-bullseye
ARG BASE_UBUNTU_IMAGE=ubuntu:22.04

# Stage 1: Get Node.js runtime
FROM ${BASE_NODE_GLIBC_IMAGE} AS node_runtime

# Stage 2: Build Audiveris Java Application
FROM ${BASE_UBUNTU_IMAGE} AS audiveris_builder

ARG TARGETARCH

ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Install system utilities and download JDK 25 directly from Adoptium
RUN set -eux; \
  apt-get update; \
  apt-get install -y --no-install-recommends ca-certificates curl git fontconfig; \
  mkdir -p /opt/java; \
  if [ "${TARGETARCH:-amd64}" = "arm64" ]; then \
    jdk_arch="aarch64"; \
  else \
    jdk_arch="x64"; \
  fi; \
  curl -fsSL -o /tmp/openjdk.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/25/ga/linux/${jdk_arch}/jdk/hotspot/normal/eclipse"; \
  tar -xzf /tmp/openjdk.tar.gz -C /opt/java --strip-components=1; \
  rm -f /tmp/openjdk.tar.gz; \
  java -version; \
  git --version

WORKDIR /opt/audiveris-src

# Copy the entire Audiveris source code from the repository context
COPY . .

# Run Gradle compilation to build Audiveris distribution using official wrapper
RUN set -eux; \
  ./gradlew --no-daemon :app:installDist

# Save the build revision
RUN git rev-parse HEAD > /opt/audiveris-build-revision.txt || echo "unknown" > /opt/audiveris-build-revision.txt


# Stage 3: Final Production Image
FROM ${BASE_UBUNTU_IMAGE}

ARG TARGETARCH
ARG TESSDATA_BASE_URL=https://raw.githubusercontent.com/tesseract-ocr/tessdata/main
ARG AUDIVERIS_TESSDATA_DIR=/usr/share/tesseract-ocr/4.00/tessdata

ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
ENV PADDLE_PDX_MODEL_SOURCE=BOS
ENV PADDLE_OCR_BASE_DIR=/root/.paddleocr
ENV PIP_ROOT_USER_ACTION=ignore

# Install runtime dependencies and Tesseract OCR
RUN set -eux; \
  apt-get update; \
  apt-get install -y --no-install-recommends \
    ca-certificates curl \
    libasound2 libbsd0 libc6 libglib2.0-0 libgl1 libmd0 libx11-6 libxau6 libxcb1 libxdmcp6 \
    libxext6 libxi6 libxrender1 libxtst6 xdg-utils zlib1g \
    fontconfig fonts-dejavu-core fonts-dejavu-extra fonts-liberation2 libgtk-3-0 \
    python3 python3-pip \
    poppler-utils \
    tesseract-ocr; \
  mkdir -p "${AUDIVERIS_TESSDATA_DIR}" /root/.config/AudiverisLtd/audiveris/tessdata; \
  # Download high-accuracy standard models directly from the official GitHub repository
  for lang in eng chi_sim; do \
    curl --retry 6 --retry-all-errors -fsSL \
      -o "${AUDIVERIS_TESSDATA_DIR}/${lang}.traineddata" \
      "${TESSDATA_BASE_URL}/${lang}.traineddata"; \
    cp "${AUDIVERIS_TESSDATA_DIR}/${lang}.traineddata" "/root/.config/AudiverisLtd/audiveris/tessdata/${lang}.traineddata"; \
  done; \
  fc-cache -f -v; \
  rm -rf /var/lib/apt/lists/*

# Copy compiled Audiveris and Java runtime from builder stage
COPY --from=audiveris_builder /opt/audiveris-src/app/build/install/app /opt/audiveris
COPY --from=audiveris_builder /opt/audiveris-build-revision.txt /opt/audiveris/BUILD_REVISION
COPY --from=audiveris_builder /opt/java /opt/java

# Setup symlinks and verify Audiveris
RUN set -eux; \
  ln -sf /opt/audiveris/bin/Audiveris /usr/local/bin/audiveris; \
  printf 'git_url=https://github.com/menlong999/audiveris.git\ngit_ref=master\n' > /opt/audiveris/BUILD_SOURCE; \
  /usr/local/bin/audiveris -batch -help >/tmp/audiveris-help.txt; \
  head -n 20 /tmp/audiveris-help.txt; \
  rm -f /tmp/audiveris-help.txt

# Copy Node.js runtime
COPY --from=node_runtime /usr/local/ /usr/local/

# Verify Node.js
RUN set -eux; \
  node -v; \
  npm -v

# Install Python AI dependencies directly from PyPI
RUN set -eux; \
  python3 -m pip install --no-cache-dir --upgrade pip setuptools wheel; \
  python3 -m pip install --no-cache-dir paddlepaddle==3.2.2 -i https://www.paddlepaddle.org.cn/packages/stable/cpu/; \
  python3 -m pip install --no-cache-dir paddleocr==3.6.0

# Pre-download and cache PaddleOCR models
RUN python3 - <<'PY'
from paddleocr import PaddleOCR, TextRecognition
TextRecognition(
    model_name="PP-OCRv5_server_rec",
    device="cpu",
    enable_mkldnn=True,
    cpu_threads=4,
)
TextRecognition(
    model_name="PP-OCRv5_mobile_rec",
    device="cpu",
    enable_mkldnn=False,
    cpu_threads=1,
)
PaddleOCR(
    lang="ch",
    device="cpu",
    use_doc_orientation_classify=False,
    use_doc_unwarping=False,
    use_textline_orientation=False,
    enable_mkldnn=True,
    cpu_threads=4,
    text_detection_model_name="PP-OCRv5_mobile_det",
    text_recognition_model_name="PP-OCRv4_server_rec_doc",
)
PY

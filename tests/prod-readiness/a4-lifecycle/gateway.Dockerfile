# Compatible with the shipped `http2 on` configuration. This image is only a CI test runner.
FROM nginx:1.28.0
RUN apt-get update -qq && apt-get install -y --no-install-recommends python3 openssl && rm -rf /var/lib/apt/lists/*
ENTRYPOINT []

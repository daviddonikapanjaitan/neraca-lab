# PostgreSQL with pgvector (the RAG vector store: rag_document / rag_chunk).
# Based on the same official image as before (postgres:17-alpine), so the existing data volume is used
# unchanged; pgvector is compiled from its release tag (portable: no -march=native, no LLVM bitcode).
FROM postgres:17-alpine

ARG PGVECTOR_VERSION=0.8.7

RUN apk add --no-cache --virtual .pgvector-build git build-base \
    && git clone --branch "v${PGVECTOR_VERSION}" --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && cd /tmp/pgvector \
    && make with_llvm=no OPTFLAGS="" \
    && make with_llvm=no install \
    && cd / \
    && rm -rf /tmp/pgvector \
    && apk del .pgvector-build
